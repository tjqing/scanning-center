package com.scan.center.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class RepositoryCatalogSaveDTO {
  @NotBlank @Size(max = 255) private String repositoryName;
  @NotBlank @Size(max = 1000) private String repositoryUrl;
  @NotBlank @Size(max = 20) private String application;
  private Boolean enabled;
}
