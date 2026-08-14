package com.scan.center.model;

import java.util.Date;
import java.util.List;
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
  private String scanSourceType;
  /** 当前快照/扫描源应用 */
  private String application;
  /** 当前快照/扫描源版本 YYYYMM */
  private String versionNo;
  private String scopeJson;
  private String status;
  private String taskType;
  private Long ownerUserId;
  private String ownerUserName;
  private Long currentSnapshotId;
  private Long currentRunId;
  private String manifestStatus;
  private Integer manifestFileCount;
  private Integer snapshotVersion;
  private Long manifestId;
  private Boolean deleted;
  private Integer totalFiles;
  private Integer completedFiles;
  private Integer successFiles;
  private Integer failedFiles;
  private Integer issueCount;
  private Boolean cancelRequested;
  private String errorMessage;
  private String scheduleType;
  private Date scheduleTime;
  private Long operatorUserId;
  private String operatorUserName;
  private Date startTime;
  private Date endTime;
  private Date createTime;
  private Date updateTime;
  /** 当前快照绑定的规则 ID（详情回显用，非表字段） */
  private List<Long> ruleIds;
}
