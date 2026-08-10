package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class TaskManifestFile {
  private Long id;
  private Long manifestId;
  private String relativePath;
  private String fileType;
  private Long fileSize;
  private Date lastModifiedTime;
  private String contentHash;
  private Integer applicableRuleCount;
}
