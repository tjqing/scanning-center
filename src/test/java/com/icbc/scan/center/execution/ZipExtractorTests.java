package com.icbc.scan.center.execution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class ZipExtractorTests {
  @Test
  public void extractsGbkChineseFileName() throws Exception {
    Path directory = Files.createTempDirectory("zip-extractor-test");
    Path zip = directory.resolve("代码库.zip");
    try (OutputStream output = Files.newOutputStream(zip);
        ZipOutputStream archive = new ZipOutputStream(output, Charset.forName("GBK"))) {
      archive.putNextEntry(new ZipEntry("项目/说明文档.txt"));
      archive.write("扫描内容".getBytes(StandardCharsets.UTF_8));
      archive.closeEntry();
    }
    Path target = directory.resolve("target");
    Files.createDirectories(target);

    ZipExtractor.extract(zip, target);

    Path extracted = target.resolve("项目/说明文档.txt");
    assertTrue(Files.exists(extracted));
    assertEquals("扫描内容", new String(Files.readAllBytes(extracted), StandardCharsets.UTF_8));
  }
}
