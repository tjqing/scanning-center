package com.scan.center.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan.center.exception.BusinessException;
import com.scan.center.model.ModelCredential;
import com.scan.center.service.SecretCodec;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class AiModelHttpClient {
  private final String url;
  private final String model;
  private final String ucidHeader;
  private final String tokenHeader;
  private final String tokenPrefix;
  private final RestTemplate restTemplate;
  private final ObjectMapper json;
  private final SecretCodec secretCodec;

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
    this.restTemplate = new RestTemplate(factory);
  }

  public String invoke(ModelCredential credential, String systemPrompt, String userPrompt) {
    if (url.isEmpty()) throw new BusinessException(60001, "未配置大模型调用URL（scan-center.ai.url / AI_MODEL_URL）");
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.set(ucidHeader, credential.getUcid());
      headers.set(tokenHeader, tokenPrefix + secretCodec.decrypt(credential.getTokenCiphertext()));
      Map<String, Object> body = new LinkedHashMap<String, Object>();
      if (!model.isEmpty()) body.put("model", model);
      List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
      messages.add(message("system", systemPrompt));
      messages.add(message("user", userPrompt));
      body.put("messages", messages);
      body.put("temperature", 0);
      body.put("response_format", Collections.singletonMap("type", "json_object"));
      ResponseEntity<String> response =
          restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<Map<String, Object>>(body, headers), String.class);
      if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null)
        throw new BusinessException(60002, "大模型HTTP调用失败：" + response.getStatusCodeValue());
      return extractContent(response.getBody());
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      throw new BusinessException(60002, "大模型HTTP调用失败：" + limit(message, 500));
    }
  }

  private Map<String, String> message(String role, String content) {
    Map<String, String> value = new LinkedHashMap<String, String>();
    value.put("role", role);
    value.put("content", content == null ? "" : content);
    return value;
  }

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

  private String limit(String value, int length) {
    return value.length() <= length ? value : value.substring(0, length);
  }
}
