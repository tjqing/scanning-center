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
  public static final String OVERVIEW = "OVERVIEW_DESIGN";
  public static final String DETAIL = "DETAIL_DESIGN";
  private final Map<String, String> versionsUrls;
  private final Map<String, String> documentsUrls;
  private final RestTemplate restTemplate;
  private final ObjectMapper json;

  public MdDocumentHttpClient(
      @Value("${scan-center.md-api.overview.versions-url:}") String overviewVersionsUrl,
      @Value("${scan-center.md-api.overview.documents-url:}") String overviewDocumentsUrl,
      @Value("${scan-center.md-api.detail.versions-url:}") String detailVersionsUrl,
      @Value("${scan-center.md-api.detail.documents-url:}") String detailDocumentsUrl,
      @Value("${scan-center.md-api.connect-timeout-ms:10000}") int connectTimeout,
      @Value("${scan-center.md-api.read-timeout-ms:120000}") int readTimeout,
      ObjectMapper json) {
    this.versionsUrls = new HashMap<String, String>(); this.documentsUrls = new HashMap<String, String>();
    this.versionsUrls.put(OVERVIEW, trim(overviewVersionsUrl)); this.documentsUrls.put(OVERVIEW, trim(overviewDocumentsUrl));
    this.versionsUrls.put(DETAIL, trim(detailVersionsUrl)); this.documentsUrls.put(DETAIL, trim(detailDocumentsUrl)); this.json = json;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectTimeout); factory.setReadTimeout(readTimeout); this.restTemplate = new RestTemplate(factory);
  }

  public List<String> versions(String documentType, String application) {
    String versionsUrl = endpoint(versionsUrls, documentType, "版本查询");
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

  public void download(String documentType, String application, String version, Path target) {
    String documentsUrl = endpoint(documentsUrls, documentType, "文档获取");
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

  private String endpoint(Map<String, String> endpoints, String documentType, String purpose) {
    if (!Arrays.asList(OVERVIEW, DETAIL).contains(documentType)) throw new BusinessException(21005, "MD文档类型不合法");
    String value = endpoints.get(documentType);
    if (value == null || value.isEmpty()) throw new BusinessException(21001, "未配置" + mdName(documentType) + purpose + "HTTP接口");
    return value;
  }

  private String mdName(String documentType) { return OVERVIEW.equals(documentType) ? "概要设计.md" : "详细设计.md"; }

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
