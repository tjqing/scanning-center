package com.scan.center.config;

import com.scan.center.common.OperatorContext;
import com.scan.center.mapper.UserMapper;
import com.scan.center.model.SystemUser;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 从请求头 X-User-Id 解析开发态操作人并写入 {@link OperatorContext}。
 * 无头或用户无效时不绑定（业务侧回退默认 admin）。
 */
@Component
public class OperatorInterceptor implements HandlerInterceptor {
  /** 前端开发态登录传递的用户 ID 请求头 */
  public static final String HEADER_USER_ID = "X-User-Id";

  private final UserMapper userMapper;

  public OperatorInterceptor(UserMapper userMapper) {
    this.userMapper = userMapper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String raw = request.getHeader(HEADER_USER_ID);
    if (raw == null || raw.trim().isEmpty()) {
      return true;
    }
    try {
      Long id = Long.valueOf(raw.trim());
      SystemUser user = userMapper.findById(id);
      if (user != null && Boolean.TRUE.equals(user.getEnabled()) && !Boolean.TRUE.equals(user.getDeleted())) {
        OperatorContext.set(user.getId(), user.getUsername(), user.getRoleCode(), user.getApplication());
      }
    } catch (NumberFormatException ignored) {
      // 非法头忽略，回退默认操作人
    }
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    OperatorContext.clear();
  }
}
