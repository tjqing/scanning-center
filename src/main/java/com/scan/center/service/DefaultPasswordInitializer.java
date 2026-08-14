package com.scan.center.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时为尚无密码摘要的用户写入默认密码摘要（kzxf-123456）。
 */
@Component
public class DefaultPasswordInitializer implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(DefaultPasswordInitializer.class);

  private final JdbcTemplate jdbc;
  private final PasswordHasher passwordHasher;

  public DefaultPasswordInitializer(JdbcTemplate jdbc, PasswordHasher passwordHasher) {
    this.jdbc = jdbc;
    this.passwordHasher = passwordHasher;
  }

  @Override
  public void run(ApplicationArguments args) {
    String hash = passwordHasher.hash(PasswordHasher.DEFAULT_PASSWORD);
    int updated = jdbc.update(
        "UPDATE system_user SET password_hash=? WHERE deleted=FALSE AND (password_hash IS NULL OR TRIM(password_hash)='')",
        hash);
    if (updated > 0) {
      log.info("已为 {} 个用户补齐默认登录密码摘要", updated);
    }
  }
}
