package com.icbc.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class SystemUser {
  private Long id;
  private String username;
  private String displayName;
  private String roleCode;
  private String phone;
  private String email;
  private String description;
  private Boolean enabled;
  private Boolean deleted;
  private Date createTime;
  private Date updateTime;
}
