package com.scan.center.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 登录密码单向摘要（SHA-256 + 配置口令作 pepper）。
 * 不可逆；与 SecretCodec（可逆 AES）用途不同。
 */
@Component
public class PasswordHasher {
  /** 默认初始密码（新建用户 / 库内空密码补齐） */
  public static final String DEFAULT_PASSWORD = "kzxf-123456";

  private final String pepper;

  public PasswordHasher(@Value("${scan-center.ai.credential-secret}") String pepper) {
    this.pepper = pepper == null ? "" : pepper;
  }

  /**
   * 明文密码转库内摘要。
   *
   * @param rawPassword 明文
   * @return Base64(SHA-256(pepper:password))
   */
  public String hash(String rawPassword) {
    if (rawPassword == null) rawPassword = "";
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest((pepper + ":" + rawPassword).getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(bytes);
    } catch (Exception e) {
      throw new IllegalStateException("密码摘要失败", e);
    }
  }

  /**
   * 校验明文是否与库内摘要一致。
   *
   * @param rawPassword 明文
   * @param passwordHash 库内摘要
   * @return 是否匹配
   */
  public boolean matches(String rawPassword, String passwordHash) {
    if (passwordHash == null || passwordHash.trim().isEmpty()) return false;
    return hash(rawPassword).equals(passwordHash.trim());
  }
}
