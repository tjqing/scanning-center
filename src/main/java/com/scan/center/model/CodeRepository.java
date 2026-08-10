package com.scan.center.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Date;
import java.util.Collections;
import java.util.List;
import lombok.Data;

@Data
public class CodeRepository {
  private Long id;
  private String repositoryName;
  private String repositoryCode;
  private String application;
  private Long repositoryCatalogId;
  private String gitProjectsJson;
  private String mdDocumentType;
  private String versionNo;
  private String scanSourceType;
  private String sourceType;
  private String description;
  private String repositoryUrl;
  private String defaultBranch;
  private String storageKey;
  private String originalFileName;
  private Boolean enabled;
  private Boolean deleted;
  private Long operatorUserId;
  private String operatorUserName;
  private Date lastScanTime;
  private Date createTime;
  private Date updateTime;

  @JsonProperty(value = "repositoryCatalogIds", access = JsonProperty.Access.READ_ONLY)
  public List<Long> repositoryCatalogIds() {
    if (gitProjectsJson == null || gitProjectsJson.trim().isEmpty()) return Collections.emptyList();
    try {
      List<GitProject> projects = new ObjectMapper().readValue(gitProjectsJson, new TypeReference<List<GitProject>>() {});
      java.util.ArrayList<Long> ids = new java.util.ArrayList<Long>();
      for (GitProject project : projects) if (project.getId() != null) ids.add(project.getId());
      return ids;
    } catch (Exception ignored) { return Collections.emptyList(); }
  }

  @Data
  public static class GitProject {
    private Long id;
    private String name;
    private String url;
    private String application;
  }
}
