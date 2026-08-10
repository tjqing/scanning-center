package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class CodeRepository {
  private Long id;
  private String repositoryName;
  private String repositoryCode;
  private String application;
  private Long repositoryCatalogId;
  private String versionNo;
  private String scanSourceType;
  private String sourceType;
  private String description;
  private String repositoryUrl;
  private String defaultBranch;
  private String storageKey;
  private String originalFileName;
  private Boolean enabled;
  private Boolean deleted;
  private Long operatorUserId;
  private String operatorUserName;
  private Date lastScanTime;
  private Date createTime;
  private Date updateTime;
}
