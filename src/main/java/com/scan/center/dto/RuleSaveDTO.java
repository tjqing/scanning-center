package com.scan.center.dto;

import javax.validation.constraints.*;
import lombok.Data;

@Data
public class RuleSaveDTO {
  @NotBlank
  @Size(max = 64)
  private String ruleCode;

  @NotBlank
  @Size(max = 128)
  private String ruleName;

  @Size(max = 1000)
  private String description;

  @NotBlank private String ruleType;
  @NotBlank private String targetType;
  @NotBlank private String riskLevel;
  private String matchType;

  @Size(max = 20000)
  private String matchContent;

  private Boolean caseSensitive;

  @Size(max = 500)
  private String fileTypes;

  @Size(max = 1000)
  private String excludePatterns;

  @Size(max = 1000)
  private String issueDescription;

  @Size(max = 1000)
  private String suggestion;

  @Size(max = 30000)
  private String promptContent;

  private Boolean enabled;
}
