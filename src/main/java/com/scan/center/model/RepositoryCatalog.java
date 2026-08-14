package com.scan.center.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Date;
import lombok.Data;

@Data
public class RepositoryCatalog {
  private Long id;
  private String repositoryName;
  private String repositoryUrl;
  private String application;
  /** YYYYMM，与 MD 版本挡板一致 */
  private String versionNo;
  /** SSH_KEY / HTTPS_TOKEN */
  private String authType;
  private String gitUsername;
  @JsonIgnore private String encryptedSecret;
  private Boolean enabled;
  private Boolean deleted;
  private Long operatorUserId;
  private String operatorUserName;
  private Date createTime;
  private Date updateTime;

  @JsonProperty("hasSecret")
  public boolean isHasSecret() {
    return encryptedSecret != null && !encryptedSecret.trim().isEmpty();
  }
}
