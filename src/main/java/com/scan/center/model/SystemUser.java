package com.scan.center.model;

import java.util.Date;
import java.util.List;
import lombok.Data;

@Data
public class SystemUser {
  private Long id;
  private String username;
  private String displayName;
  private String externalUserId;
  private String application;
  private String roleCode;
  private List<Long> repositoryIds;
  private List<String> repositoryNames;
  private String description;
  private Boolean enabled;
  private Boolean deleted;
  private Date createTime;
  private Date updateTime;
}
