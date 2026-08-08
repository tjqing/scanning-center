package com.scan.center;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@MapperScan("com.scan.center.mapper")
@SpringBootApplication
public class ScanningCenterApplication {
  public static void main(String[] args) {
    SpringApplication.run(ScanningCenterApplication.class, args);
  }
}
