package com.scan.center.model;

import java.util.Date;
import lombok.Data;

@Data
public class TaskManifestFile {
  private Long id;
  private Long manifestId;
  private String relativePath;
  private String fileType;
  private Long fileSize;
  private Date lastModifiedTime;
  private String contentHash;
  private Integer applicableRuleCount;
  /** 当前运行扫描状态：NONE/PENDING/RUNNING/SUCCESS/FAILED */
  private String scanStatus;
  /** 是否已有 AI 返回报文可查看 */
  private Boolean hasAiResponse;
  private String scanErrorMessage;
  /** AI 请求报文字符数（当前运行，执行单元汇总） */
  private Integer requestChars;
  /** AI 请求 token 数（当前运行，执行单元汇总） */
  private Integer promptTokens;
}
