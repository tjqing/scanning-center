package com.scan.center.execution;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 安全 ZIP 解压工具：防路径穿越、限制条目数与解压体积，兼容 UTF-8 / GBK 文件名。
 */
public final class ZipExtractor {
  /** 单包最多解压条目数 */
  private static final int MAX_ENTRIES = 10000;
  /** 解压后累计字节上限（约 500MB） */
  private static final long MAX_EXPANDED_SIZE = 500L * 1024 * 1024;
  /** Windows 中文环境常见 ZIP 文件名编码 */
  private static final Charset GBK = Charset.forName("GBK");

  /** 工具类禁止实例化 */
  private ZipExtractor() {}

  /**
   * 将 ZIP 解压到目标根目录。
   *
   * @param zip  ZIP 文件路径
   * @param root 解压根目录（必须已存在或可创建父目录）
   * @throws IOException 超限、路径非法、编码不支持或 IO 失败
   */
  public static void extract(Path zip, Path root) throws IOException {
    Charset charset = detectCharset(zip);
    long total = 0;
    int count = 0;
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip), charset)) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        if (++count > MAX_ENTRIES) throw new IOException("压缩包文件数量超限");
        Path target = root.resolve(entry.getName()).normalize();
        if (!target.startsWith(root)) throw new IOException("压缩包包含非法路径");
        if (entry.isDirectory()) {
          Files.createDirectories(target);
          continue;
        }
        Files.createDirectories(target.getParent());
        try (OutputStream output = Files.newOutputStream(target)) {
          byte[] buffer = new byte[8192];
          int length;
          while ((length = input.read(buffer)) > 0) {
            total += length;
            if (total > MAX_EXPANDED_SIZE) throw new IOException("压缩包解压大小超限");
            output.write(buffer, 0, length);
          }
        }
      }
    } catch (IllegalArgumentException e) {
      throw new IOException("ZIP文件名编码不受支持，请使用UTF-8或GBK编码重新压缩", e);
    }
  }

  /**
   * 探测 ZIP 条目名编码：优先 UTF-8，失败则尝试 GBK。
   *
   * @param zip ZIP 路径
   * @return 可用字符集
   * @throws IOException 两种编码均无法枚举条目时抛出
   */
  private static Charset detectCharset(Path zip) throws IOException {
    try {
      validateNames(zip, StandardCharsets.UTF_8);
      return StandardCharsets.UTF_8;
    } catch (IllegalArgumentException e) {
      validateNames(zip, GBK);
      return GBK;
    }
  }

  /**
   * 用指定字符集打开 ZIP 并遍历全部条目名，用于校验编码是否可读。
   *
   * @param zip     ZIP 路径
   * @param charset 尝试的字符集
   * @throws IOException              打开失败
   * @throws IllegalArgumentException 编码无法解码条目名
   */
  private static void validateNames(Path zip, Charset charset) throws IOException {
    try (ZipFile file = new ZipFile(zip.toFile(), charset)) {
      Enumeration<? extends ZipEntry> entries = file.entries();
      while (entries.hasMoreElements()) entries.nextElement().getName();
    }
  }
}
