package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class ScanResult {
  private Long id;
  private Long taskId;
  private String taskNo;
  private String taskName;
  private String repositoryName;
  private Integer scannedFiles;
  private Integer successFiles;
  private Integer failedFiles;
  private Integer issueCount;
  private Integer highCount;
  private Integer mediumCount;
  private Integer lowCount;
  private Integer infoCount;
  private Long operatorUserId;
  private String operatorUserName;
  private Date createTime;
}
