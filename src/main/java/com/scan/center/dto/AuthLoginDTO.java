package com.scan.center.dto;

import javax.validation.constraints.NotBlank;
import lombok.Data;

/** 登录入参：用户名 + 密码。 */
@Data
public class AuthLoginDTO {
  /** 登录用户名 */
  @NotBlank private String username;
  /** 明文密码（传输后由服务端摘要比对） */
  @NotBlank private String password;
}
