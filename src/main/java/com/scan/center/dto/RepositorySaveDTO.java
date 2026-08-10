package com.scan.center.dto;

import javax.validation.constraints.*;
import java.util.List;
import lombok.Data;

@Data
public class RepositorySaveDTO {
  @Size(max = 128)
  private String repositoryName;

  @Size(max = 64)
  private String repositoryCode;

  @Size(max = 128)
  private String application;

  private Long repositoryCatalogId;

  private List<Long> repositoryCatalogIds;

  private String gitProjectsJson;

  @Size(max = 30)
  private String mdDocumentType;

  @Size(max = 20)
  private String versionNo;

  @NotBlank private String scanSourceType;

  @NotBlank private String sourceType;

  @Size(max = 1000)
  private String description;

  @Size(max = 1000)
  private String repositoryUrl;

  @Size(max = 128)
  private String defaultBranch;

  private Boolean enabled;
}
