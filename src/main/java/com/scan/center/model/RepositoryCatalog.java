package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class RepositoryCatalog {
  private Long id;
  private String repositoryName;
  private String repositoryUrl;
  private String application;
  private Boolean enabled;
  private Boolean deleted;
  private Long operatorUserId;
  private String operatorUserName;
  private Date createTime;
  private Date updateTime;
}
