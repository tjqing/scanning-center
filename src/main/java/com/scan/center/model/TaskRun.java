package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class TaskRun {
  private Long id;
  private String runNo;
  private Long taskId;
  private Long taskSnapshotId;
  private Long manifestId;
  private String status;
  private Boolean stopRequested;
  private Integer resumeCount;
  private Integer totalUnits;
  private Integer completedUnits;
  private Integer successUnits;
  private Integer failedUnits;
  private Integer issueCount;
  private Long startedByUserId;
  private String startedByUserName;
  private String errorMessage;
  private Date queueTime;
  private Date startTime;
  private Date endTime;
  private Date lastCheckpointTime;
  private Date createTime;
  private Date updateTime;
}
