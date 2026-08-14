package com.scan.center.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 详细设计文档装配结果：可直接拼接到 AI 请求上下文。
 *
 * 任务启动后将 {@link #getAiContextText()} 拼入 user prompt；
 * 若 {@link #isFound()} 为 false，前端应提示「数据库没有」并询问是否跳转 iframe 自行下载上传（仅 .md）。
 */
public class DetailDesignAiContext {
  /** 是否在 doc_info 中查到可用正文 */
  private boolean found;
  /** 提示信息（如：数据库没有 / 已装配 N 条子条目） */
  private String message;
  /** 应用 ID */
  private String application;
  /** 版本号 */
  private String versionNo;
  /** 命中的需求子条目编号列表 */
  private List<String> subitemNos = new ArrayList<String>();
  /** 已按子条目顺序拼接好的 Markdown 正文（可落盘为「详细设计.md」） */
  private String markdownContent;
  /** 已装配好、可直接拼进 AI user/system 上下文的完整文本 */
  private String aiContextText;
  /** 无结果时前端跳转下载页（iframe）的地址，有结果时可为空 */
  private String downloadPageUrl;

  public boolean isFound() { return found; }
  public void setFound(boolean found) { this.found = found; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public String getApplication() { return application; }
  public void setApplication(String application) { this.application = application; }
  public String getVersionNo() { return versionNo; }
  public void setVersionNo(String versionNo) { this.versionNo = versionNo; }
  public List<String> getSubitemNos() { return subitemNos; }
  public void setSubitemNos(List<String> subitemNos) { this.subitemNos = subitemNos; }
  public String getMarkdownContent() { return markdownContent; }
  public void setMarkdownContent(String markdownContent) { this.markdownContent = markdownContent; }
  public String getAiContextText() { return aiContextText; }
  public void setAiContextText(String aiContextText) { this.aiContextText = aiContextText; }
  public String getDownloadPageUrl() { return downloadPageUrl; }
  public void setDownloadPageUrl(String downloadPageUrl) { this.downloadPageUrl = downloadPageUrl; }
}
