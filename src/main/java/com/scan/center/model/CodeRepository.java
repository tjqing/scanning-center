package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class CodeRepository {
  private Long id;
  private String repositoryName;
  private String repositoryCode;
  private String application;
  private String scanSourceType;
  private String sourceType;
  private String description;
  private String repositoryUrl;
  private String username;
  private String encryptedToken;
  private String defaultBranch;
  private String scanPaths;
  private String excludePatterns;
  private String fileTypes;
  private String storageKey;
  private String originalFileName;
  private String databaseUrl;
  private String databaseUsername;
  private String encryptedDatabasePassword;
  private String documentQuery;
  private String documentNameColumn;
  private String documentContentColumn;
  private String documentTypeColumn;
  private Boolean enabled;
  private Boolean deleted;
  private Long operatorUserId;
  private String operatorUserName;
  private Date lastScanTime;
  private Date createTime;
  private Date updateTime;
}
