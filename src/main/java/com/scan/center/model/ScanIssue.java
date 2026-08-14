package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class ScanIssue {
  private Long id;
  private Long resultId;
  private Long taskId;
  private Long runId;
  private Long executionUnitId;
  private String resultCommitKey;
  private Long repositoryId;
  private Long ruleId;
  private String title;
  private String riskLevel;
  private String ruleType;
  private String filePath;
  private Integer startLine;
  private Integer endLine;
  private String matchedContent;
  private String contextContent;
  private String issueDescription;
  private String suggestion;
  private String status;
  private String handleComment;
  private String handler;
  private Long operatorUserId;
  private String operatorUserName;
  private Date handleTime;
  private Date createTime;
  private String taskName;
  private String taskNo;
  private String application;
  private String versionNo;
  private String repositoryName;
  private String ruleName;
}
