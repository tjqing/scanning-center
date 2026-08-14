package com.scan.center.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 敏感凭证编解码（AES/GCM）。
 *用于 SSH 私钥、AI Token 等落库密文；密钥来自配置 {@code scan-center.ai.credential-secret}。
 */
@Component
public class SecretCodec {
  /** AES 密钥（由配置口令 SHA-256 摘要后截取前 16 字节派生） */
  private final SecretKeySpec key;
  /** 用于生成每次加密的随机 IV */
  private final SecureRandom random = new SecureRandom();

  /**
   * 根据配置口令初始化 AES 密钥。
   *
   * @param secret 配置项 credential-secret，不可为空
   * @throws Exception 摘要算法不可用时抛出
   */
  public SecretCodec(@Value("${scan-center.ai.credential-secret}") String secret) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
    this.key = new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
  }

  /**
   * 明文加密为 Base64(IV + ciphertext+tag)。
   *
   * @param value 明文；null 或空串则原样返回
   * @return Base64 密文
   * @throws IllegalStateException 加密失败
   */
  public String encrypt(String value) {
    if (value == null || value.isEmpty()) return value;
    try {
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      byte[] all = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, all, 0, iv.length);
      System.arraycopy(encrypted, 0, all, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(all);
    } catch (Exception e) {
      throw new IllegalStateException("凭证加密失败", e);
    }
  }

  /**
   * 将 {@link #encrypt(String)} 产出的密文还原为明文。
   *
   * @param value Base64 密文；null 或空串则原样返回
   * @return 明文
   * @throws IllegalStateException 解密失败（口令变更、密文损坏等）
   */
  public String decrypt(String value) {
    if (value == null || value.isEmpty()) return value;
    try {
      byte[] all = Base64.getDecoder().decode(value);
      byte[] iv = Arrays.copyOfRange(all, 0, 12);
      byte[] encrypted = Arrays.copyOfRange(all, 12, all.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("凭证解密失败", e);
    }
  }
}
