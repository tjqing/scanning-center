package com.scan.center.common;

/**
 * 当前 HTTP 请求的操作人上下文（开发态登录通过 X-User-Id 注入）。
 * 无 Web 请求（定时任务等）时为空，业务侧回退默认 admin。
 */
public final class OperatorContext {
  private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<Holder>();

  private OperatorContext() {}

  /** 绑定当前操作人；请求结束须 {@link #clear()}。 */
  public static void set(Long userId, String username, String roleCode, String application) {
    Holder h = new Holder();
    h.userId = userId;
    h.username = username;
    h.roleCode = roleCode;
    h.application = application;
    HOLDER.set(h);
  }

  /** 清除 ThreadLocal，避免线程池复用串号。 */
  public static void clear() {
    HOLDER.remove();
  }

  /** @return 当前用户 ID，未绑定返回 null */
  public static Long userId() {
    Holder h = HOLDER.get();
    return h == null ? null : h.userId;
  }

  /** @return 当前用户名，未绑定返回 null */
  public static String username() {
    Holder h = HOLDER.get();
    return h == null ? null : h.username;
  }

  /** @return 角色编码，未绑定返回 null */
  public static String roleCode() {
    Holder h = HOLDER.get();
    return h == null ? null : h.roleCode;
  }

  /** @return 应用编码，未绑定返回 null */
  public static String application() {
    Holder h = HOLDER.get();
    return h == null ? null : h.application;
  }

  /** @return 是否已绑定操作人 */
  public static boolean present() {
    return HOLDER.get() != null && HOLDER.get().userId != null;
  }

  private static final class Holder {
    private Long userId;
    private String username;
    private String roleCode;
    private String application;
  }
}
