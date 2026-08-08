package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class ScanTask {
  private Long id;
  private String taskNo;
  private String taskName;
  private String description;
  private Long repositoryId;
  private String repositoryName;
  private String repositorySourceType;
  private String repositoryFileName;
  private String scopeJson;
  private String status;
  private Integer totalFiles;
  private Integer completedFiles;
  private Integer successFiles;
  private Integer failedFiles;
  private Integer issueCount;
  private Boolean cancelRequested;
  private String errorMessage;
  private Long operatorUserId;
  private String operatorUserName;
  private Date startTime;
  private Date endTime;
  private Date createTime;
  private Date updateTime;
}
