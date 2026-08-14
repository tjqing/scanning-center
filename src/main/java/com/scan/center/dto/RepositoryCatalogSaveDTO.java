package com.scan.center.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class RepositoryCatalogSaveDTO {
  @NotBlank @Size(max = 255) private String repositoryName;
  @NotBlank @Size(max = 1000) private String repositoryUrl;
  @NotBlank @Size(max = 20) private String application;
  /** YYYYMM */
  @NotBlank @Size(max = 20) private String versionNo;
  /** 可选：SSH_KEY / HTTPS_TOKEN；前端不传时后端默认 SSH_KEY */
  @Size(max = 20) private String authType;
  @Size(max = 128) private String gitUsername;
  /** 明文私钥/Token；留空则由后端 applyGitCredential 处理 */
  private String secret;
  private Boolean enabled;
}
