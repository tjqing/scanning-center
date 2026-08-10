package com.scan.center.dto;

import javax.validation.constraints.*;
import lombok.Data;

@Data
public class UserSaveDTO {
  @NotBlank @Size(max = 64) private String username;
  @NotBlank @Size(max = 128) private String displayName;
  @Size(max = 128) private String externalUserId;
  @Size(max = 128) private String application;
  @NotBlank private String roleCode;
  @Size(max = 32) private String phone;
  @Email @Size(max = 128) private String email;
  @Size(max = 500) private String description;
  private Boolean enabled;
}
