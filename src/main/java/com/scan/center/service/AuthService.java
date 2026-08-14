package com.scan.center.service;

import com.scan.center.common.OperatorContext;
import com.scan.center.exception.BusinessException;
import com.scan.center.mapper.UserMapper;
import com.scan.center.model.SystemUser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 登录认证：用户名 + 密码摘要校验；后续可换 SSO。
 */
@Service
public class AuthService {
  /** 未登录或用户无效时的业务码，前端据此清会话跳登录 */
  public static final int CODE_UNAUTHORIZED = 40101;
  /** 用户名或密码错误（不区分，避免枚举用户） */
  public static final int CODE_BAD_CREDENTIAL = 40102;

  private final UserMapper userMapper;
  private final PasswordHasher passwordHasher;

  public AuthService(UserMapper userMapper, PasswordHasher passwordHasher) {
    this.userMapper = userMapper;
    this.passwordHasher = passwordHasher;
  }

  /**
   * 登录：按用户名查启用用户并校验密码。
   *
   * @param username 用户名
   * @param password 明文密码
   * @return 用户简要信息
   */
  public Map<String, Object> login(String username, String password) {
    if (username == null || username.trim().isEmpty()) {
      throw new BusinessException(CODE_BAD_CREDENTIAL, "用户名或密码错误");
    }
    SystemUser user = userMapper.findEnabledByUsername(username.trim());
    if (user == null || !passwordHasher.matches(password, user.getPasswordHash())) {
      throw new BusinessException(CODE_BAD_CREDENTIAL, "用户名或密码错误");
    }
    return brief(user);
  }

  /**
   * 当前请求操作人（必须带有效 X-User-Id）。
   *
   * @return 用户简要信息
   * @throws BusinessException 未登录或用户无效（40101）
   */
  public Map<String, Object> me() {
    if (!OperatorContext.present()) {
      throw new BusinessException(CODE_UNAUTHORIZED, "未登录或用户已失效，请重新登录");
    }
    SystemUser user = userMapper.findById(OperatorContext.userId());
    if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
      throw new BusinessException(CODE_UNAUTHORIZED, "未登录或用户已失效，请重新登录");
    }
    return brief(user);
  }

  /** 退出：无服务端会话，仅占位。 */
  public void logout() {}

  private Map<String, Object> brief(SystemUser user) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("id", user.getId());
    m.put("username", user.getUsername());
    m.put("displayName", user.getDisplayName());
    m.put("roleCode", user.getRoleCode());
    m.put("application", user.getApplication());
    return m;
  }
}
