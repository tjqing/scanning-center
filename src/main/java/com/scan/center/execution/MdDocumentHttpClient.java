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

/**
 * MD 设计文档 HTTP 客户端。
 *
 * 通过配置的 REST 接口查询版本列表并下载概要/详细设计文档到本地目录。
 */
@Component
public class MdDocumentHttpClient {

  /** 概要设计文档类型标识，对应 {@code scan-center.md-api.overview.*} 配置。 */
  public static final String OVERVIEW = "OVERVIEW_DESIGN";

  /** 详细设计文档类型标识，对应 {@code scan-center.md-api.detail.*} 配置。 */
  public static final String DETAIL = "DETAIL_DESIGN";

  /** 文档类型 → 版本查询接口 URL。 */
  private final Map<String, String> versionsUrls;

  /** 文档类型 → 文档下载接口 URL。 */
  private final Map<String, String> documentsUrls;

  /** 用于发起 GET 请求的 HTTP 客户端，超时由配置注入。 */
  private final RestTemplate restTemplate;

  /** JSON 响应解析器。 */
  private final ObjectMapper json;

  /**
   * 根据配置初始化各文档类型的接口地址与 HTTP 客户端。
   *
   * @param overviewVersionsUrl  概要设计版本查询 URL
   * @param overviewDocumentsUrl 概要设计文档下载 URL
   * @param detailVersionsUrl    详细设计版本查询 URL
   * @param detailDocumentsUrl   详细设计文档下载 URL
   * @param connectTimeout       连接超时（毫秒）
   * @param readTimeout          读取超时（毫秒）
   * @param json                 JSON 解析器
   */
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

  /**
   * 查询指定应用在某文档类型下的可用版本号列表。
   *
   * 仅保留 6 位数字格式的版本号，按降序返回。
   *
   * @param documentType 文档类型，取 {@link #OVERVIEW} 或 {@link #DETAIL}
   * @param application  应用标识
   * @return 版本号列表，无匹配时返回空列表
   * @throws BusinessException 文档类型非法、接口未配置或 HTTP 调用失败
   */
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

  /**
   * 下载指定应用、版本的设计文档并写入目标目录。
   *
   * 文件名格式为 {@code 序号-安全文件名}，支持 Base64 或明文内容字段。
   *
   * @param documentType 文档类型，取 {@link #OVERVIEW} 或 {@link #DETAIL}
   * @param application  应用标识
   * @param version      6 位版本号
   * @param target       本地保存目录，需已存在或可创建
   * @throws BusinessException 文档类型非法、接口未配置或 HTTP 调用失败
   * @throws IllegalArgumentException 文档数量/大小超限、路径穿越或响应无文档
   */
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

  /** 发起 GET 请求并解析 JSON 响应体。 */
  private JsonNode get(URI uri) throws Exception {
    ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity.EMPTY, String.class);
    if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) throw new IllegalArgumentException("HTTP状态" + response.getStatusCodeValue());
    return json.readTree(response.getBody());
  }

  /**
   * 按文档类型解析已配置的接口 URL。
   *
   * @throws BusinessException 类型非法或对应 URL 未配置
   */
  private String endpoint(Map<String, String> endpoints, String documentType, String purpose) {
    if (!Arrays.asList(OVERVIEW, DETAIL).contains(documentType)) throw new BusinessException(21005, "MD文档类型不合法");
    String value = endpoints.get(documentType);
    if (value == null || value.isEmpty()) throw new BusinessException(21001, "未配置" + mdName(documentType) + purpose + "HTTP接口");
    return value;
  }

  /** 将文档类型常量映射为中文展示名。 */
  private String mdName(String documentType) { return OVERVIEW.equals(documentType) ? "概要设计.md" : "详细设计.md"; }

  /** 拼装带 {@code application}、可选 {@code version} 查询参数的 URI。 */
  private URI uri(String url, String application, String version) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url).queryParam("application", application);
    if (version != null) builder.queryParam("version", version); return builder.build().encode().toUri();
  }

  /**
   * 从响应 JSON 中提取数组字段，兼容根数组、{@code data} 包裹及多字段名。
   *
   * @throws IllegalArgumentException 未找到目标数组
   */
  private JsonNode array(JsonNode root, String field) {
    if (root.isArray()) return root;
    JsonNode data = root.path("data");
    if (data.isArray()) return data;
    if (data.path(field).isArray()) return data.path(field);
    if (root.path(field).isArray()) return root.path(field);
    throw new IllegalArgumentException("HTTP响应中缺少" + field + "数组");
  }

  /** 按候选字段名顺序读取首个非空文本值，均不存在时返回 {@code null}。 */
  private String text(JsonNode node, String... names) {
    for (String name : names) if (node.path(name).isValueNode() && !node.path(name).isNull()) return node.path(name).asText(); return null;
  }

  /** 去除路径成分并将非法文件名字符替换为下划线。 */
  private String safeName(String value) { return Paths.get(value).getFileName().toString().replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_"); }

  /** 去除首尾空白，{@code null} 视为空串。 */
  private static String trim(String value) { return value == null ? "" : value.trim(); }

  /** 优先返回非空 {@code value}，否则返回 {@code fallback}。 */
  private String first(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value; }

  /** 截断字符串至指定长度，{@code null} 返回固定占位文案。 */
  private String limit(String value, int length) { if (value == null) return "未知错误"; return value.length() <= length ? value : value.substring(0, length); }
}
