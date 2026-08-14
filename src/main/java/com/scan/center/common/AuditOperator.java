package com.scan.center.common;

/**
 * 审计操作人：优先取 {@link OperatorContext}（开发态登录），否则回退默认 admin（id=1）。
 */
public final class AuditOperator {
  /** 默认操作人 ID（无请求上下文时回退） */
  public static final Long USER_ID = 1L;
  /** 默认操作人用户名 */
  public static final String USER_NAME = "admin";

  private AuditOperator() {}

  /**
   * 当前写操作应使用的用户 ID。
   *
   * @return 上下文用户 ID，或默认 1
   */
  public static Long userId() {
    Long id = OperatorContext.userId();
    return id == null ? USER_ID : id;
  }

  /**
   * 当前写操作应使用的用户名。
   *
   * @return 上下文用户名，或默认 admin
   */
  public static String userName() {
    String name = OperatorContext.username();
    return name == null || name.trim().isEmpty() ? USER_NAME : name;
  }
}
