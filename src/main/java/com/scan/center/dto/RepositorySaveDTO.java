package com.scan.center.dto;

import javax.validation.constraints.*;
import lombok.Data;

@Data
public class RepositorySaveDTO {
  @NotBlank
  @Size(max = 128)
  private String repositoryName;

  @Size(max = 64)
  private String repositoryCode;

  @Size(max = 128)
  private String application;

  @Size(max = 1000)
  private String designDocumentPath;

  @NotBlank private String sourceType;

  @Size(max = 1000)
  private String description;

  @Size(max = 1000)
  private String repositoryUrl;

  @Size(max = 128)
  private String username;

  @Size(max = 2000)
  private String token;

  @Size(max = 128)
  private String defaultBranch;

  @Size(max = 1000)
  private String scanPaths;

  @Size(max = 1000)
  private String excludePatterns;

  @Size(max = 500)
  private String fileTypes;

  @Size(max = 1000) private String databaseUrl;
  @Size(max = 128) private String databaseUsername;
  @Size(max = 2000) private String databasePassword;
  @Size(max = 4000) private String documentQuery;
  @Size(max = 128) private String documentNameColumn;
  @Size(max = 128) private String documentContentColumn;
  @Size(max = 128) private String documentTypeColumn;

  private Boolean enabled;
}
