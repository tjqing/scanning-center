package com.scan.center.execution;

/**
 * 一次大模型 HTTP 调用的完整结果，供落库、清单展示与排查。
 *字符数由本地按请求体统计；Token 优先取响应 {@code usage}，未返回时为 null。
 */
public class AiCallResult {
  /** 模型返回的正文内容（一般为 choices[0].message.content） */
  private final String content;
  /** 发出的完整请求 JSON 字符串 */
  private final String rawRequest;
  /** 收到的完整响应 JSON 字符串 */
  private final String rawResponse;
  /** system 提示词字符数 */
  private final int systemChars;
  /** user 提示词字符数 */
  private final int userChars;
  /** 请求侧总字符数（system + user 等） */
  private final int requestChars;
  /** 响应正文字符数 */
  private final int responseChars;
  /** 本次 HTTP 调用耗时（毫秒） */
  private final long costMs;
  /** 来自响应 usage.prompt_tokens；未返回时为 null */
  private final Integer promptTokens;
  /** 来自响应 usage.completion_tokens；未返回时为 null */
  private final Integer completionTokens;
  /** 来自响应 usage.total_tokens；未返回时为 null */
  private final Integer totalTokens;

  /**
   * @param content           模型正文
   * @param rawRequest        原始请求 JSON
   * @param rawResponse       原始响应 JSON
   * @param systemChars       system 字符数
   * @param userChars         user 字符数
   * @param requestChars      请求总字符数
   * @param responseChars     响应字符数
   * @param costMs            耗时毫秒
   * @param promptTokens      提示 Token，可为 null
   * @param completionTokens  补全 Token，可为 null
   * @param totalTokens       总 Token，可为 null
   */
  public AiCallResult(
      String content,
      String rawRequest,
      String rawResponse,
      int systemChars,
      int userChars,
      int requestChars,
      int responseChars,
      long costMs,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens) {
    this.content = content;
    this.rawRequest = rawRequest;
    this.rawResponse = rawResponse;
    this.systemChars = systemChars;
    this.userChars = userChars;
    this.requestChars = requestChars;
    this.responseChars = responseChars;
    this.costMs = costMs;
    this.promptTokens = promptTokens;
    this.completionTokens = completionTokens;
    this.totalTokens = totalTokens;
  }

  /** @return 模型正文 */
  public String getContent() { return content; }
  /** @return 原始请求 JSON */
  public String getRawRequest() { return rawRequest; }
  /** @return 原始响应 JSON */
  public String getRawResponse() { return rawResponse; }
  /** @return system 字符数 */
  public int getSystemChars() { return systemChars; }
  /** @return user 字符数 */
  public int getUserChars() { return userChars; }
  /** @return 请求总字符数 */
  public int getRequestChars() { return requestChars; }
  /** @return 响应字符数 */
  public int getResponseChars() { return responseChars; }
  /** @return 耗时毫秒 */
  public long getCostMs() { return costMs; }
  /** @return 提示 Token，可能为 null */
  public Integer getPromptTokens() { return promptTokens; }
  /** @return 补全 Token，可能为 null */
  public Integer getCompletionTokens() { return completionTokens; }
  /** @return 总 Token，可能为 null */
  public Integer getTotalTokens() { return totalTokens; }
}
