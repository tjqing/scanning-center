package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class ModelCredential {
  private Long id;
  private Long userId;
  private String credentialName;
  private String ucid;
  private String tokenCiphertext;
  private String tokenMasked;
  private Boolean enabled;
  private String runtimeStatus;
  private String leaseId;
  private Long leasedRunId;
  private Date leaseExpireTime;
  private Date cooldownUntil;
  private Date lastUsedTime;
  private Date lastTestTime;
  private Date createTime;
  private Date updateTime;
}
