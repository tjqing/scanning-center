package com.scan.center.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class ModelCredentialSaveDTO {
  @NotBlank @Size(max = 128) private String credentialName;
  @NotBlank @Size(max = 255) private String ucid;
  @Size(max = 2000) private String token;
  private Boolean enabled;
}
