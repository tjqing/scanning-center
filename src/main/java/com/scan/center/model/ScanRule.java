package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class ScanRule {
  private Long id;
  private String ruleCode;
  private String ruleName;
  private String description;
  private String ruleType;
  private String targetType;
  private String riskLevel;
  private String matchType;
  private String matchContent;
  private Boolean caseSensitive;
  private String fileTypes;
  private String excludePatterns;
  private String issueDescription;
  private String suggestion;
  private String promptContent;
  private String checkRuleContent;
  private String resultUpdateContent;
  private String visibility;
  private Long ownerUserId;
  private String ownerUserName;
  private Long sharedByUserId;
  private String sharedByUserName;
  private Date sharedTime;
  private Boolean enabled;
  private Boolean deleted;
  private Long operatorUserId;
  private String operatorUserName;
  private Date createTime;
  private Date updateTime;
}
