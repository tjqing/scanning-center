package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class TaskSnapshot {
  private Long id;
  private Long taskId;
  private Integer snapshotVersion;
  private String taskNameSnapshot;
  private String descriptionSnapshot;
  private String taskType;
  private String scanSourceType;
  private Long repositoryId;
  private String sourceSnapshot;
  private String application;
  private String versionNo;
  private String scanPaths;
  private String fileTypes;
  private String excludePaths;
  private String promptSnapshot;
  private String snapshotHash;
  private String serverRootPath;
  private Long createdByUserId;
  private String createdByUserName;
  private Date createTime;
}
