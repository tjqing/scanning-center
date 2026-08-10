package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class ModelPromptTemplate {
  private Long id;
  private String promptType;
  private Integer versionNo;
  private String promptContent;
  private String jsonSchema;
  private String status;
  private Long operatorUserId;
  private String operatorUserName;
  private Date createTime;
  private Date updateTime;
}
