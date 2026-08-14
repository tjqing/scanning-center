package com.scan.center.controller;

import com.scan.center.common.ApiResponse;
import com.scan.center.dto.AuthLoginDTO;
import com.scan.center.service.AuthService;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 登录 / 退出 / 当前用户。后续接 SSO 时替换本控制器实现即可。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService service;

  public AuthController(AuthService service) {
    this.service = service;
  }

  /** 用户名 + 密码登录。 */
  @PostMapping("/login")
  public ApiResponse<Map<String, Object>> login(@Validated @RequestBody AuthLoginDTO dto) {
    return ApiResponse.success(service.login(dto.getUsername(), dto.getPassword()));
  }

  /** 刷新当前操作人（需请求头 X-User-Id）。 */
  @GetMapping("/me")
  public ApiResponse<Map<String, Object>> me() {
    return ApiResponse.success(service.me());
  }

  /** 退出（前端清本地会话）。 */
  @PostMapping("/logout")
  public ApiResponse<Void> logout() {
    service.logout();
    return ApiResponse.success(null);
  }
}
