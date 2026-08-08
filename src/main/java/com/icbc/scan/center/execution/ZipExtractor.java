package com.icbc.scan.center.execution;

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

/** 安全 ZIP 解压工具，兼容 UTF-8 和 Windows 中文环境常见的 GBK 文件名。 */
public final class ZipExtractor {
  private static final int MAX_ENTRIES = 10000;
  private static final long MAX_EXPANDED_SIZE = 500L * 1024 * 1024;
  private static final Charset GBK = Charset.forName("GBK");

  private ZipExtractor() {}

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

  private static Charset detectCharset(Path zip) throws IOException {
    try {
      validateNames(zip, StandardCharsets.UTF_8);
      return StandardCharsets.UTF_8;
    } catch (IllegalArgumentException e) {
      validateNames(zip, GBK);
      return GBK;
    }
  }

  private static void validateNames(Path zip, Charset charset) throws IOException {
    try (ZipFile file = new ZipFile(zip.toFile(), charset)) {
      Enumeration<? extends ZipEntry> entries = file.entries();
      while (entries.hasMoreElements()) entries.nextElement().getName();
    }
  }
}
