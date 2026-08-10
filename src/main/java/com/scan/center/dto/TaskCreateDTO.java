package com.scan.center.dto;

import java.util.List;
import javax.validation.constraints.*;
import lombok.Data;

@Data
public class TaskCreateDTO {
  @NotBlank
  @Size(max = 128)
  private String taskName;

  @Size(max = 1000)
  private String description;

  @NotNull private Long repositoryId;
  @NotEmpty private List<Long> ruleIds;

  private java.util.List<String> scanPaths;
  private java.util.List<String> fileTypes;
  private java.util.List<String> excludePaths;

  @Size(max = 20)
  private String versionNo;

  @Size(max = 5000)
  private String scopeJson;

  private Boolean executeImmediately;
}
