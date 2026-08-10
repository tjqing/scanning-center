package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class TaskManifest {
  private Long id;
  private Long taskSnapshotId;
  private Integer manifestVersion;
  private String status;
  private Integer fileCount;
  private Integer excludedCount;
  private Long totalSize;
  private String excludeSummary;
  private String manifestHash;
  private String errorMessage;
  private Date startTime;
  private Date finishTime;
  private Date createTime;
}
