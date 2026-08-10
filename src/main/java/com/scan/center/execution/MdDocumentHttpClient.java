package com.scan.center.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan.center.exception.BusinessException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class MdDocumentHttpClient {
  private final String versionsUrl;
  private final String documentsUrl;
  private final RestTemplate restTemplate;
  private final ObjectMapper json;

  public MdDocumentHttpClient(
      @Value("${scan-center.md-api.versions-url:}") String versionsUrl,
      @Value("${scan-center.md-api.documents-url:}") String documentsUrl,
      @Value("${scan-center.md-api.connect-timeout-ms:10000}") int connectTimeout,
      @Value("${scan-center.md-api.read-timeout-ms:120000}") int readTimeout,
      ObjectMapper json) {
    this.versionsUrl = trim(versionsUrl); this.documentsUrl = trim(documentsUrl); this.json = json;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectTimeout); factory.setReadTimeout(readTimeout); this.restTemplate = new RestTemplate(factory);
  }

  public List<String> versions(String application) {
    if (versionsUrl.isEmpty()) throw new BusinessException(21001, "未配置MD版本查询HTTP接口（MD_VERSIONS_URL）");
    try {
      JsonNode root = get(uri(versionsUrl, application, null)); JsonNode values = array(root, "versions");
      LinkedHashSet<String> result = new LinkedHashSet<String>();
      for (JsonNode value : values) {
        String version = value.isTextual() ? value.asText() : text(value, "version", "versionNo");
        if (version != null && version.matches("\\d{6}")) result.add(version);
      }
      List<String> sorted = new ArrayList<String>(result); Collections.sort(sorted, Collections.reverseOrder()); return sorted;
    } catch (BusinessException e) { throw e; }
    catch (Exception e) { throw new BusinessException(21002, "MD版本HTTP接口调用失败：" + limit(e.getMessage(), 500)); }
  }

  public void download(String application, String version, Path target) {
    if (documentsUrl.isEmpty()) throw new BusinessException(21003, "未配置MD文档获取HTTP接口（MD_DOCUMENTS_URL）");
    try {
      JsonNode root = get(uri(documentsUrl, application, version)); JsonNode documents = array(root, "documents");
      int index = 0;
      for (JsonNode document : documents) {
        if (++index > 10000) throw new IllegalArgumentException("MD文档数量超过10000条限制");
        String name = first(text(document, "name", "documentName", "title"), "document-" + index);
        String type = first(text(document, "type", "fileType"), "txt");
        String safe = safeName(name); if (!safe.contains(".")) safe += "." + safeName(type).toLowerCase(Locale.ROOT);
        Path file = target.resolve(String.format("%05d-%s", index, safe)).normalize();
        if (!file.startsWith(target)) throw new IllegalArgumentException("MD文档名称非法");
        String base64 = text(document, "contentBase64", "base64Content");
        byte[] content = base64 == null ? first(text(document, "content", "documentContent"), "").getBytes(StandardCharsets.UTF_8) : Base64.getDecoder().decode(base64);
        if (content.length > 50 * 1024 * 1024) throw new IllegalArgumentException("单个MD文档超过50MB限制");
        Files.write(file, content);
      }
      if (index == 0) throw new IllegalArgumentException("MD文档接口未返回文档");
    } catch (BusinessException e) { throw e; }
    catch (Exception e) { throw new BusinessException(21004, "MD文档HTTP接口调用失败：" + limit(e.getMessage(), 500)); }
  }

  private JsonNode get(URI uri) throws Exception {
    ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity.EMPTY, String.class);
    if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) throw new IllegalArgumentException("HTTP状态" + response.getStatusCodeValue());
    return json.readTree(response.getBody());
  }

  private URI uri(String url, String application, String version) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url).queryParam("application", application);
    if (version != null) builder.queryParam("version", version); return builder.build().encode().toUri();
  }

  private JsonNode array(JsonNode root, String field) {
    if (root.isArray()) return root;
    JsonNode data = root.path("data");
    if (data.isArray()) return data;
    if (data.path(field).isArray()) return data.path(field);
    if (root.path(field).isArray()) return root.path(field);
    throw new IllegalArgumentException("HTTP响应中缺少" + field + "数组");
  }

  private String text(JsonNode node, String... names) {
    for (String name : names) if (node.path(name).isValueNode() && !node.path(name).isNull()) return node.path(name).asText(); return null;
  }
  private String safeName(String value) { return Paths.get(value).getFileName().toString().replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_"); }
  private static String trim(String value) { return value == null ? "" : value.trim(); }
  private String first(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value; }
  private String limit(String value, int length) { if (value == null) return "未知错误"; return value.length() <= length ? value : value.substring(0, length); }
}
