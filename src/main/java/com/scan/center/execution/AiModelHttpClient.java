package com.scan.center.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan.center.exception.BusinessException;
import com.scan.center.model.ModelCredential;
import com.scan.center.service.SecretCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 大模型 HTTP 调用客户端。
 *
 * 封装 Chat Completions 风格接口的请求组装、鉴权、UTF-8 响应解析及 Token 用量统计。
 */
@Component
public class AiModelHttpClient {
  private static final Logger log = LoggerFactory.getLogger(AiModelHttpClient.class);

  /**
   * 追加到 system 提示词末尾的 JSON 输出契约，约束模型返回 {@code issues} 数组结构。
   */
  private static final String SYSTEM_ISSUES_CONTRACT =
      "【输出格式强制要求】你必须只输出一个 JSON 对象，禁止输出 Markdown/解释文字。"
          + "顶层必须包含 issues 数组；无问题时返回 {\"issues\":[]}。"
          + "issues 每个元素字段："
          + "title(string), severity(HIGH|MEDIUM|LOW|INFO), startLine(number), endLine(number), "
          + "evidence(string), description(string), suggestion(string)。"
          + "【语言强制要求】title、description（问题说明）、suggestion（整改建议）必须使用简体中文撰写，禁止英文段落；"
          + "evidence 可保留原文代码片段。"
          + "【行号强制要求】用户提供的代码为 \"行号|原文\" 格式；startLine/endLine 必须严格等于左侧行号，禁止估算。"
          + "示例：{\"issues\":[{\"title\":\"示例问题\",\"severity\":\"HIGH\",\"startLine\":1,\"endLine\":1,"
          + "\"evidence\":\"代码片段\",\"description\":\"问题说明必须用中文\",\"suggestion\":\"整改建议必须用中文\"}]}";

  /** 大模型接口 URL，来自 {@code scan-center.ai.url}。 */
  private final String url;

  /** 请求体中的 model 字段，空则省略。 */
  private final String model;

  /** UCID 鉴权请求头名称。 */
  private final String ucidHeader;

  /** Token 鉴权请求头名称。 */
  private final String tokenHeader;

  /** Token 值前缀，通常与 Bearer 组合使用。 */
  private final String tokenPrefix;

  /** 使用 UTF-8 消息转换器的 HTTP 客户端。 */
  private final RestTemplate restTemplate;

  /** JSON 序列化/反序列化器。 */
  private final ObjectMapper json;

  /** 凭证密文解密器。 */
  private final SecretCodec secretCodec;

  /**
   * 注入配置并初始化 UTF-8 {@link RestTemplate}。
   *
   * @param url            大模型 API 地址
   * @param model          模型名称，可为空
   * @param ucidHeader     UCID 请求头名
   * @param tokenHeader    Token 请求头名
   * @param tokenPrefix    Token 前缀
   * @param connectTimeout 连接超时（毫秒）
   * @param readTimeout    读取超时（毫秒）
   * @param json           JSON 解析器
   * @param secretCodec    凭证解密服务
   */
  public AiModelHttpClient(
      @Value("${scan-center.ai.url:}") String url,
      @Value("${scan-center.ai.model:}") String model,
      @Value("${scan-center.ai.ucid-header:X-UCID}") String ucidHeader,
      @Value("${scan-center.ai.token-header:Authorization}") String tokenHeader,
      @Value("${scan-center.ai.token-prefix:Bearer }") String tokenPrefix,
      @Value("${scan-center.ai.connect-timeout-ms:10000}") int connectTimeout,
      @Value("${scan-center.ai.read-timeout-ms:120000}") int readTimeout,
      ObjectMapper json,
      SecretCodec secretCodec) {
    this.url = url == null ? "" : url.trim();
    this.model = model == null ? "" : model.trim();
    this.ucidHeader = ucidHeader;
    this.tokenHeader = tokenHeader;
    this.tokenPrefix = tokenPrefix == null ? "" : tokenPrefix;
    this.json = json;
    this.secretCodec = secretCodec;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectTimeout);
    factory.setReadTimeout(readTimeout);
    this.restTemplate = buildUtf8RestTemplate(factory);
  }

  /**
   * 构建使用 UTF-8 解码字符串响应的 {@link RestTemplate}。
   *
   * Spring 默认 {@link StringHttpMessageConverter} 使用 ISO-8859-1，会导致中文内容乱码。
   */
  private static RestTemplate buildUtf8RestTemplate(SimpleClientHttpRequestFactory factory) {
    RestTemplate template = new RestTemplate(factory);
    List<HttpMessageConverter<?>> converters = template.getMessageConverters();
    for (int i = 0; i < converters.size(); i++) {
      if (converters.get(i) instanceof StringHttpMessageConverter) {
        StringHttpMessageConverter utf8 = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        utf8.setWriteAcceptCharset(false);
        converters.set(i, utf8);
      }
    }
    return template;
  }

  /**
   * 调用大模型并仅返回文本内容（兼容旧接口）。
   *
   * @param credential   模型凭证，含 UCID 与加密 Token
   * @param systemPrompt 系统提示词，会与输出契约合并
   * @param userPrompt   用户提示词
   * @return 模型返回的文本内容
   * @throws BusinessException URL 未配置或 HTTP 调用失败
   */
  public String invoke(ModelCredential credential, String systemPrompt, String userPrompt) {
    return invokeDetailed(credential, systemPrompt, userPrompt).getContent();
  }

  /**
   * 调用大模型并返回完整结果，含原始请求/响应报文及 Token 统计，供落库使用。
   *
   * @param credential   模型凭证，含 UCID 与加密 Token
   * @param systemPrompt 系统提示词，会与输出契约合并
   * @param userPrompt   用户提示词
   * @return 包含内容、报文、耗时及 Token 用量的调用结果
   * @throws BusinessException URL 未配置或 HTTP 调用失败
   */
  public AiCallResult invokeDetailed(ModelCredential credential, String systemPrompt, String userPrompt) {
    if (url.isEmpty()) throw new BusinessException(60001, "未配置大模型调用URL（scan-center.ai.url / AI_MODEL_URL）");
    try {
      String system = mergeSystem(systemPrompt);
      String user = userPrompt == null ? "" : userPrompt;

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
      headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
      headers.setAcceptCharset(Collections.singletonList(StandardCharsets.UTF_8));
      headers.set(ucidHeader, credential.getUcid());
      headers.set(tokenHeader, tokenPrefix + secretCodec.decrypt(credential.getTokenCiphertext()));

      Map<String, Object> body = new LinkedHashMap<String, Object>();
      if (!model.isEmpty()) body.put("model", model);
      List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
      messages.add(message("system", system));
      messages.add(message("user", user));
      body.put("messages", messages);
      body.put("temperature", 0);
      body.put("response_format", Collections.singletonMap("type", "json_object"));

      String requestJson = json.writeValueAsString(body);
      log.info(
          "AI请求 url={}, model={}, credentialId={}, systemChars={}, userChars={}, requestChars={}, estPromptTokens≈{}, estRequestTokens≈{}",
          url, model, credential.getId(),
          system.length(), user.length(), requestJson.length(),
          estimateTokens(system) + estimateTokens(user), estimateTokens(requestJson));

      long start = System.currentTimeMillis();
      // 按字节读取再 UTF-8 解码，避免 Content-Type 缺 charset 时被当成 Latin-1
      ResponseEntity<byte[]> response =
          restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<Map<String, Object>>(body, headers), byte[].class);
      long costMs = System.currentTimeMillis() - start;

      if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null)
        throw new BusinessException(60002, "大模型HTTP调用失败：" + response.getStatusCodeValue());

      String raw = new String(response.getBody(), StandardCharsets.UTF_8);
      Usage usage = readUsage(raw);
      String content = extractContent(raw);

      // 优先 AI 返回的 usage；缺字段时用本地估算兜底
      int estPrompt = estimateTokens(system) + estimateTokens(user);
      int estCompletion = estimateTokens(content);
      Integer promptTokens = usage.promptTokens != null ? usage.promptTokens : Integer.valueOf(estPrompt);
      Integer completionTokens = usage.completionTokens != null ? usage.completionTokens : Integer.valueOf(estCompletion);
      Integer totalTokens = usage.totalTokens != null
          ? usage.totalTokens
          : Integer.valueOf(promptTokens.intValue() + completionTokens.intValue());

      log.info(
          "AI响应 status={}, costMs={}, responseChars={}, prompt_tokens={}{}, completion_tokens={}{}, total_tokens={}{}",
          response.getStatusCodeValue(), costMs, raw.length(),
          promptTokens, usage.promptTokens == null ? "(est)" : "",
          completionTokens, usage.completionTokens == null ? "(est)" : "",
          totalTokens, usage.totalTokens == null ? "(est)" : "");

      return new AiCallResult(
          content, requestJson, raw, system.length(), user.length(), requestJson.length(), raw.length(), costMs,
          promptTokens, completionTokens, totalTokens);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      log.warn("AI调用异常: {}", message, e);
      throw new BusinessException(60002, "大模型HTTP调用失败：" + limit(message, 500));
    }
  }

  /** 将业务 system 提示词与 {@link #SYSTEM_ISSUES_CONTRACT} 合并；空提示词时仅使用契约。 */
  private String mergeSystem(String systemPrompt) {
    String base = systemPrompt == null ? "" : systemPrompt.trim();
    if (base.isEmpty()) return SYSTEM_ISSUES_CONTRACT;
    return base + "\n\n" + SYSTEM_ISSUES_CONTRACT;
  }

  /** 构造单条 chat message（role + content）。 */
  private Map<String, String> message(String role, String content) {
    Map<String, String> value = new LinkedHashMap<String, String>();
    value.put("role", role);
    value.put("content", content == null ? "" : content);
    return value;
  }

  /**
   * 从响应 JSON 中提取模型文本，兼容 choices/message/content、顶层 content/data 及 issues 直出。
   *
   * @throws IllegalArgumentException 响应中无可解析内容
   */
  private String extractContent(String raw) throws Exception {
    JsonNode root = json.readTree(raw);
    JsonNode choices = root.path("choices");
    if (choices.isArray() && choices.size() > 0) {
      JsonNode content = choices.get(0).path("message").path("content");
      if (content.isTextual()) return content.asText();
    }
    JsonNode content = root.path("content");
    if (content.isTextual()) return content.asText();
    JsonNode data = root.path("data");
    if (data.isTextual()) return data.asText();
    if (root.has("issues")) return root.toString();
    throw new IllegalArgumentException("响应中未找到可解析的模型内容");
  }

  /** 解析响应 {@code usage} 节点中的 Token 计数，解析失败时返回空用量。 */
  private Usage readUsage(String raw) {
    Usage usage = new Usage();
    try {
      JsonNode node = json.readTree(raw).path("usage");
      if (node.isMissingNode() || node.isNull()) return usage;
      usage.promptTokens = readTokenInt(node, "prompt_tokens");
      usage.completionTokens = readTokenInt(node, "completion_tokens");
      usage.totalTokens = readTokenInt(node, "total_tokens");
    } catch (Exception ignored) {
      // ignore
    }
    return usage;
  }

  /** 从 usage 节点读取整型 Token 字段，兼容数字与字符串格式。 */
  private Integer readTokenInt(JsonNode usage, String field) {
    JsonNode node = usage.path(field);
    if (node.isMissingNode() || node.isNull()) return null;
    if (node.isNumber()) return Integer.valueOf(node.asInt());
    if (node.isTextual()) {
      String text = node.asText().trim();
      if (text.isEmpty() || "-".equals(text)) return null;
      try { return Integer.valueOf(Integer.parseInt(text)); }
      catch (NumberFormatException ignored) { return null; }
    }
    return null;
  }

  /**
   * 估算文本 Token 数：汉字按 1.5 字/token、其余按 4 字符/token，并与 UTF-8 字节启发式取较大值。
   */
  private int estimateTokens(String text) {
    if (text == null || text.isEmpty()) return 0;
    int cjk = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) cjk++;
    }
    int byCjk = (int) Math.ceil(cjk / 1.5) + (int) Math.ceil((text.length() - cjk) / 4.0);
    int byBytes = (int) Math.ceil(text.getBytes(StandardCharsets.UTF_8).length / 4.0);
    return Math.max(byCjk, byBytes);
  }

  /** 截断字符串至指定长度。 */
  private String limit(String value, int length) {
    return value.length() <= length ? value : value.substring(0, length);
  }

  /** 大模型响应 {@code usage} 节点的 Token 计数载体。 */
  private static final class Usage {

    /** 输入 Token 数。 */
    private Integer promptTokens;

    /** 输出 Token 数。 */
    private Integer completionTokens;

    /** 总 Token 数。 */
    private Integer totalTokens;
  }
}
