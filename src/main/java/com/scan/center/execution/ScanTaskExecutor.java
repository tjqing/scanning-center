package com.scan.center.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan.center.common.AuditOperator;
import com.scan.center.model.*;
import com.scan.center.service.ModelConfigService;
import com.scan.center.service.TaskService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScanTaskExecutor {
  private static final Logger log = LoggerFactory.getLogger(ScanTaskExecutor.class);
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ModelConfigService modelConfig;
  private final AiModelHttpClient aiClient;
  private final ApplicationEventPublisher events;
  private final int maxContentLength;

  public ScanTaskExecutor(
      JdbcTemplate jdbc,
      ObjectMapper json,
      ModelConfigService modelConfig,
      AiModelHttpClient aiClient,
      ApplicationEventPublisher events,
      @Value("${scan-center.ai.max-content-length:30000}") int maxContentLength) {
    this.jdbc = jdbc;
    this.json = json;
    this.modelConfig = modelConfig;
    this.aiClient = aiClient;
    this.events = events;
    this.maxContentLength = Math.max(1000, maxContentLength);
  }

  @Async
  @EventListener
  public void onQueued(TaskService.TaskQueuedEvent event) {
    execute(event.getRunId());
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverOnStartup() {
    recoverExpiredWork();
    List<Long> queued = jdbc.query("SELECT id FROM scan_task_run WHERE status='QUEUED' ORDER BY queue_time,id", (rs, row) -> rs.getLong(1));
    for (Long id : queued) events.publishEvent(new TaskService.TaskQueuedEvent(id));
  }

  @Scheduled(fixedDelay = 60000L)
  public void recoverExpiredWork() {
    Timestamp stale = new Timestamp(System.currentTimeMillis() - 5 * 60 * 1000L);
    jdbc.update("UPDATE task_execution_unit SET status='PENDING',lease_id=NULL,lease_expire_time=NULL,update_time=CURRENT_TIMESTAMP WHERE status='RUNNING' AND update_time<?", stale);
    List<Long> staleRuns = jdbc.query(
        "SELECT id FROM scan_task_run WHERE status IN ('RUNNING','STOP_REQUESTED') AND update_time<?",
        new Object[] {stale}, (rs, row) -> rs.getLong(1));
    for (Long runId : staleRuns) {
      Boolean stop = jdbc.queryForObject("SELECT stop_requested FROM scan_task_run WHERE id=?", new Object[] {runId}, Boolean.class);
      String status = Boolean.TRUE.equals(stop) ? "PAUSED" : "QUEUED";
      jdbc.update("UPDATE scan_task_run SET status=?,update_time=CURRENT_TIMESTAMP WHERE id=?", status, runId);
      jdbc.update("UPDATE scan_task SET status=?,update_time=CURRENT_TIMESTAMP WHERE current_run_id=?", status, runId);
      if ("QUEUED".equals(status)) events.publishEvent(new TaskService.TaskQueuedEvent(runId));
    }
  }

  public void execute(Long runId) {
    if (jdbc.update("UPDATE scan_task_run SET status='RUNNING',start_time=COALESCE(start_time,CURRENT_TIMESTAMP),update_time=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'", runId) != 1) return;
    RunContext context = null;
    try {
      context = context(runId);
      jdbc.update("UPDATE scan_task SET status='RUNNING',start_time=COALESCE(start_time,CURRENT_TIMESTAMP),update_time=CURRENT_TIMESTAMP WHERE id=?", context.taskId);
      ensureResult(context);
      while (true) {
        String runStatus = jdbc.queryForObject("SELECT status FROM scan_task_run WHERE id=?", new Object[] {runId}, String.class);
        if ("STOP_REQUESTED".equals(runStatus)) {
          pause(context); return;
        }
        ExecutionUnit unit = nextUnit(runId);
        if (unit == null) break;
        process(context, unit);
        refreshProgress(context);
      }
      finish(context);
    } catch (Exception e) {
      String message = limit(e.getMessage(), 1800);
      log.error("scan run failed runId={}", runId, e);
      jdbc.update("UPDATE scan_task_run SET status='FAILED',error_message=?,end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", message, runId);
      if (context != null) jdbc.update("UPDATE scan_task SET status='FAILED',error_message=?,end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", message, context.taskId);
    }
  }

  private ExecutionUnit nextUnit(Long runId) {
    List<ExecutionUnit> values = jdbc.query(
        "SELECT u.id,u.run_id,u.manifest_file_id,u.task_snapshot_rule_id,u.stage,u.status,u.preliminary_result,u.retry_count,u.result_commit_key,f.relative_path,f.content_hash,sr.rule_snapshot FROM task_execution_unit u JOIN task_scan_manifest_file f ON f.id=u.manifest_file_id JOIN task_snapshot_rule sr ON sr.id=u.task_snapshot_rule_id WHERE u.run_id=? AND (u.status='PENDING' OR (u.status='FAILED' AND u.retry_count<2)) ORDER BY u.id LIMIT 1",
        new Object[] {runId}, (rs, row) -> unit(rs));
    if (values.isEmpty()) return null;
    ExecutionUnit unit = values.get(0);
    String lease = UUID.randomUUID().toString();
    Timestamp expiry = new Timestamp(System.currentTimeMillis() + 5 * 60 * 1000L);
    int changed = jdbc.update(
        "UPDATE task_execution_unit SET status='RUNNING',lease_id=?,lease_expire_time=?,start_time=COALESCE(start_time,CURRENT_TIMESTAMP),update_time=CURRENT_TIMESTAMP WHERE id=? AND (status='PENDING' OR (status='FAILED' AND retry_count<2))",
        lease, expiry, unit.id);
    if (changed != 1) return nextUnit(runId);
    unit.leaseId = lease;
    return unit;
  }

  private void process(RunContext context, ExecutionUnit unit) {
    try {
      Path root = Paths.get(context.serverRootPath).toAbsolutePath().normalize();
      Path file = root.resolve(unit.relativePath).normalize();
      if (!file.startsWith(root) || !Files.isRegularFile(file) || !unit.contentHash.equals(sha256(file)))
        throw new IOException("清单文件缺失或内容已变化");
      ScanRule rule = json.readValue(unit.ruleSnapshot, ScanRule.class);
      String content = parse(file);
      if ("NORMAL".equals(context.taskType)) scanNormal(context, unit, rule, content);
      else scanWithAi(context, unit, rule, content);
      jdbc.update(
          "UPDATE task_execution_unit SET status='SUCCESS',checkpoint_data=?,finish_time=CURRENT_TIMESTAMP,lease_id=NULL,lease_expire_time=NULL,error_message=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND lease_id=?",
          "{\"completed\":true}", unit.id, unit.leaseId);
    } catch (Exception e) {
      jdbc.update(
          "UPDATE task_execution_unit SET status='FAILED',retry_count=retry_count+1,error_message=?,lease_id=NULL,lease_expire_time=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND lease_id=?",
          limit(e.getMessage(), 1800), unit.id, unit.leaseId);
      log.warn("scan execution unit failed runId={}, unitId={}, file={}", context.runId, unit.id, unit.relativePath, e);
    }
  }

  private void scanNormal(RunContext context, ExecutionUnit unit, ScanRule rule, String original) throws Exception {
    String content = stripComments(original, extension(unit.relativePath));
    String[] lines = content.split("\\r?\\n", -1);
    String[] originalLines = original.split("\\r?\\n", -1);
    Pattern pattern = "REGEX".equals(rule.getMatchType())
        ? Pattern.compile(rule.getMatchContent(), Boolean.TRUE.equals(rule.getCaseSensitive()) ? 0 : Pattern.CASE_INSENSITIVE)
        : Pattern.compile(Pattern.quote(rule.getMatchContent()), Boolean.TRUE.equals(rule.getCaseSensitive()) ? 0 : Pattern.CASE_INSENSITIVE);
    int issueIndex = 0;
    for (int i = 0; i < lines.length; i++) {
      Matcher matcher = pattern.matcher(lines[i]);
      while (matcher.find()) {
        insertIssue(context, unit, rule, unit.relativePath, i + 1, i + 1,
            limit(matcher.group(), 500),
            limit(originalLines[Math.min(i, originalLines.length - 1)], 1500), rule.getIssueDescription(), rule.getSuggestion(), issueIndex++);
        if (issueIndex >= 10000) return;
      }
    }
  }

  private void scanWithAi(RunContext context, ExecutionUnit unit, ScanRule rule, String content) throws Exception {
    ModelCredential credential = waitForCredential(context.ownerUserId, context.runId);
    try {
      String bounded = content.length() <= maxContentLength ? content : content.substring(0, maxContentLength);
      String response;
      if ("CODE".equals(context.scanSourceType)) {
        String preliminary = unit.preliminaryResult;
        if (blank(preliminary)) {
          String checkPrompt = prompt(context.promptSnapshot, "AI_CHECK");
          String request = "检查规则：\n" + rule.getCheckRuleContent() + "\n文件：" + unit.relativePath + "\n内容：\n" + bounded;
          preliminary = normalizeJson(aiClient.invoke(credential, checkPrompt, request));
          validateModelJson(preliminary);
          jdbc.update("UPDATE task_execution_unit SET preliminary_result=?,checkpoint_data=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND lease_id=?",
              preliminary, "{\"stage\":\"AI_CHECK_COMPLETED\"}", unit.id, unit.leaseId);
        }
        String updatePrompt = prompt(context.promptSnapshot, "AI_RESULT_UPDATE");
        String request = "结果更新规则：\n" + rule.getResultUpdateContent() + "\n文件：" + unit.relativePath + "\n初步结果：\n" + preliminary;
        response = normalizeJson(aiClient.invoke(credential, updatePrompt, request));
      } else {
        String mdPrompt = prompt(context.promptSnapshot, "MD_CHECK");
        String request = "应用：" + safe(context.application) + "\n版本：" + safe(context.versionNo)
            + "\n检查规则：\n" + rule.getCheckRuleContent() + "\n文档：" + unit.relativePath + "\n内容：\n" + bounded;
        response = normalizeJson(aiClient.invoke(credential, mdPrompt, request));
      }
      JsonNode issues = validateModelJson(response);
      int index = 0;
      for (JsonNode issue : issues) {
        int start = Math.max(1, issue.path("startLine").asInt(1));
        int end = Math.max(start, issue.path("endLine").asInt(start));
        String risk = issue.path("severity").asText(rule.getRiskLevel());
        if (!Arrays.asList("HIGH", "MEDIUM", "LOW", "INFO").contains(risk)) risk = rule.getRiskLevel();
        insertIssue(context, unit, rule, unit.relativePath, start, end,
            limit(issue.path("evidence").asText(""), 1000), limit(issue.path("evidence").asText(""), 1500),
            limit(issue.path("description").asText(rule.getIssueDescription()), 2000),
            limit(issue.path("suggestion").asText(rule.getSuggestion()), 2000), index++,
            limit(issue.path("title").asText(rule.getRuleName()), 500), risk);
      }
    } finally {
      modelConfig.releaseCredential(credential);
    }
  }

  private ModelCredential waitForCredential(Long userId, Long runId) throws InterruptedException {
    for (int i = 0; i < 120; i++) {
      ModelCredential value = modelConfig.acquireCredential(userId, runId);
      if (value != null) return value;
      String status = jdbc.queryForObject("SELECT status FROM scan_task_run WHERE id=?", new Object[] {runId}, String.class);
      if ("STOP_REQUESTED".equals(status) || "PAUSED".equals(status)) throw new IllegalStateException("运行已请求停止");
      Thread.sleep(1000L);
    }
    throw new IllegalStateException("等待空闲模型凭证超时");
  }

  private void insertIssue(RunContext context, ExecutionUnit unit, ScanRule rule, String path,
      int start, int end, String matched, String sourceContext, String description, String suggestion, int index) {
    insertIssue(context, unit, rule, path, start, end, matched, sourceContext, description, suggestion, index, rule.getRuleName(), rule.getRiskLevel());
  }

  private void insertIssue(RunContext context, ExecutionUnit unit, ScanRule rule, String path,
      int start, int end, String matched, String sourceContext, String description, String suggestion,
      int index, String title, String risk) {
    String commit = unit.resultCommitKey + ":" + index;
    Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM scan_issue WHERE result_commit_key=?", new Object[] {commit}, Integer.class);
    if (exists != null && exists > 0) return;
    jdbc.update(
        "INSERT INTO scan_issue(result_id,task_id,run_id,repository_id,rule_id,execution_unit_id,result_commit_key,title,risk_level,rule_type,file_path,start_line,end_line,matched_content,context_content,issue_description,suggestion,status,operator_user_id,operator_user_name) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?)",
        context.resultId, context.taskId, context.runId, context.repositoryId, rule.getId(), unit.id, commit,
        title, risk, rule.getRuleType(), path, start, end, matched, sourceContext, description, suggestion,
        AuditOperator.USER_ID, AuditOperator.USER_NAME);
  }

  private void refreshProgress(RunContext context) {
    int total = count("SELECT COUNT(*) FROM task_execution_unit WHERE run_id=?", context.runId);
    int successUnits = count("SELECT COUNT(*) FROM task_execution_unit WHERE run_id=? AND status='SUCCESS'", context.runId);
    int failedUnits = count("SELECT COUNT(*) FROM task_execution_unit WHERE run_id=? AND status='FAILED' AND retry_count>=2", context.runId);
    int completedFiles = count("SELECT COUNT(*) FROM task_scan_manifest_file f WHERE f.manifest_id=? AND NOT EXISTS(SELECT 1 FROM task_execution_unit u WHERE u.run_id=? AND u.manifest_file_id=f.id AND (u.status IN ('PENDING','RUNNING') OR (u.status='FAILED' AND u.retry_count<2)))", context.manifestId, context.runId);
    int successFiles = count("SELECT COUNT(*) FROM task_scan_manifest_file f WHERE f.manifest_id=? AND NOT EXISTS(SELECT 1 FROM task_execution_unit u WHERE u.run_id=? AND u.manifest_file_id=f.id AND u.status<>'SUCCESS')", context.manifestId, context.runId);
    int failedFiles = Math.max(0, completedFiles - successFiles);
    int issues = count("SELECT COUNT(*) FROM scan_issue WHERE run_id=?", context.runId);
    jdbc.update("UPDATE scan_task_run SET total_units=?,completed_units=?,success_units=?,failed_units=?,issue_count=?,last_checkpoint_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?",
        total, successUnits + failedUnits, successUnits, failedUnits, issues, context.runId);
    jdbc.update("UPDATE scan_task SET completed_files=?,success_files=?,failed_files=?,issue_count=?,update_time=CURRENT_TIMESTAMP WHERE id=?",
        completedFiles, successFiles, failedFiles, issues, context.taskId);
    refreshResult(context, completedFiles, successFiles, failedFiles, issues);
  }

  private void refreshResult(RunContext context, int completed, int success, int failed, int issues) {
    int high = count("SELECT COUNT(*) FROM scan_issue WHERE run_id=? AND risk_level='HIGH'", context.runId);
    int medium = count("SELECT COUNT(*) FROM scan_issue WHERE run_id=? AND risk_level='MEDIUM'", context.runId);
    int low = count("SELECT COUNT(*) FROM scan_issue WHERE run_id=? AND risk_level='LOW'", context.runId);
    int info = count("SELECT COUNT(*) FROM scan_issue WHERE run_id=? AND risk_level='INFO'", context.runId);
    jdbc.update("UPDATE scan_result SET scanned_files=?,success_files=?,failed_files=?,issue_count=?,high_count=?,medium_count=?,low_count=?,info_count=? WHERE id=?",
        completed, success, failed, issues, high, medium, low, info, context.resultId);
  }

  private void finish(RunContext context) {
    refreshProgress(context);
    int failed = count("SELECT COUNT(*) FROM task_execution_unit WHERE run_id=? AND status='FAILED'", context.runId);
    int success = count("SELECT COUNT(*) FROM task_execution_unit WHERE run_id=? AND status='SUCCESS'", context.runId);
    String status = failed == 0 ? "SUCCESS" : (success == 0 ? "FAILED" : "PARTIAL_SUCCESS");
    jdbc.update("UPDATE scan_task_run SET status=?,end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", status, context.runId);
    jdbc.update("UPDATE scan_task SET status=?,end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", status, context.taskId);
  }

  private void pause(RunContext context) {
    jdbc.update("UPDATE scan_task_run SET status='PAUSED',update_time=CURRENT_TIMESTAMP WHERE id=?", context.runId);
    jdbc.update("UPDATE scan_task SET status='PAUSED',update_time=CURRENT_TIMESTAMP WHERE id=?", context.taskId);
  }

  private void ensureResult(RunContext context) {
    List<Long> ids = jdbc.query("SELECT id FROM scan_result WHERE run_id=?", new Object[] {context.runId}, (rs, row) -> rs.getLong(1));
    if (!ids.isEmpty()) { context.resultId = ids.get(0); return; }
    KeyHolder key = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("INSERT INTO scan_result(task_id,run_id,operator_user_id,operator_user_name) VALUES(?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, context.taskId); ps.setLong(2, context.runId); ps.setLong(3, context.ownerUserId); ps.setString(4, context.ownerUserName); return ps;
    }, key);
    context.resultId = key.getKey().longValue();
  }

  private RunContext context(Long runId) {
    return jdbc.queryForObject(
        "SELECT r.id AS run_id,r.task_id,r.task_snapshot_id,r.manifest_id,t.repository_id,t.owner_user_id,t.owner_user_name,s.task_type,s.scan_source_type,s.application,s.version_no,s.prompt_snapshot,s.server_root_path FROM scan_task_run r JOIN scan_task t ON t.id=r.task_id JOIN task_snapshot s ON s.id=r.task_snapshot_id WHERE r.id=?",
        new Object[] {runId}, (rs, row) -> {
          RunContext v = new RunContext(); v.runId = rs.getLong("run_id"); v.taskId = rs.getLong("task_id");
          v.snapshotId = rs.getLong("task_snapshot_id"); v.manifestId = rs.getLong("manifest_id"); v.repositoryId = rs.getLong("repository_id");
          v.ownerUserId = rs.getLong("owner_user_id"); v.ownerUserName = rs.getString("owner_user_name");
          v.taskType = rs.getString("task_type"); v.scanSourceType = rs.getString("scan_source_type");
          v.application = rs.getString("application"); v.versionNo = rs.getString("version_no");
          v.promptSnapshot = rs.getString("prompt_snapshot"); v.serverRootPath = rs.getString("server_root_path"); return v;
        });
  }

  private ExecutionUnit unit(ResultSet rs) throws SQLException {
    ExecutionUnit v = new ExecutionUnit(); v.id = rs.getLong("id"); v.runId = rs.getLong("run_id");
    v.manifestFileId = rs.getLong("manifest_file_id"); v.snapshotRuleId = rs.getLong("task_snapshot_rule_id");
    v.stage = rs.getString("stage"); v.status = rs.getString("status"); v.preliminaryResult = rs.getString("preliminary_result");
    v.retryCount = rs.getInt("retry_count"); v.resultCommitKey = rs.getString("result_commit_key");
    v.relativePath = rs.getString("relative_path"); v.contentHash = rs.getString("content_hash"); v.ruleSnapshot = rs.getString("rule_snapshot"); return v;
  }

  private String prompt(String snapshot, String type) throws Exception {
    JsonNode node = json.readTree(snapshot).path(type).path("promptContent");
    if (!node.isTextual() || blank(node.asText())) throw new IllegalStateException("任务快照缺少提示词：" + type);
    return node.asText();
  }

  private JsonNode validateModelJson(String value) throws Exception {
    JsonNode root = json.readTree(value);
    JsonNode issues = root.isArray() ? root : root.path("issues");
    if (!issues.isArray()) throw new IllegalArgumentException("模型响应缺少issues数组");
    return issues;
  }

  private String normalizeJson(String value) {
    String result = value == null ? "" : value.trim();
    if (result.startsWith("```")) {
      int firstLine = result.indexOf('\n'); int end = result.lastIndexOf("```");
      if (firstLine >= 0 && end > firstLine) result = result.substring(firstLine + 1, end).trim();
    }
    int object = result.indexOf('{'), array = result.indexOf('[');
    int start = object < 0 ? array : (array < 0 ? object : Math.min(object, array));
    int end = Math.max(result.lastIndexOf('}'), result.lastIndexOf(']'));
    return start >= 0 && end >= start ? result.substring(start, end + 1) : result;
  }

  private String parse(Path path) throws Exception {
    String type = extension(path.getFileName().toString());
    if ("docx".equals(type)) {
      StringBuilder out = new StringBuilder();
      try (XWPFDocument document = new XWPFDocument(Files.newInputStream(path))) {
        int index = 0; for (XWPFParagraph paragraph : document.getParagraphs()) out.append("[段落").append(++index).append("] ").append(paragraph.getText()).append('\n');
      }
      return out.toString();
    }
    if ("pdf".equals(type)) {
      try (PDDocument document = PDDocument.load(path.toFile())) { return new PDFTextStripper().getText(document); }
    }
    if (Files.size(path) > 10L * 1024 * 1024) throw new IOException("单文件大小超过10MB");
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  private String stripComments(String content, String type) {
    if (Arrays.asList("xml", "html", "vue").contains(type)) return stripDelimited(content, "<!--", "-->");
    if (Arrays.asList("py", "sh", "yml", "yaml", "properties").contains(type)) return stripLineComment(content, '#');
    if ("sql".equals(type)) return stripCStyle(content, true);
    if (Arrays.asList("java", "js", "ts", "css", "json").contains(type)) return stripCStyle(content, false);
    return content;
  }

  private String stripCStyle(String value, boolean sql) {
    StringBuilder out = new StringBuilder(value); char quote = 0; boolean escape = false, line = false, block = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i), next = i + 1 < value.length() ? value.charAt(i + 1) : 0;
      if (line) { if (c == '\n' || c == '\r') line = false; else out.setCharAt(i, ' '); continue; }
      if (block) { if (c == '*' && next == '/') { out.setCharAt(i, ' '); out.setCharAt(++i, ' '); block = false; } else if (c != '\n' && c != '\r') out.setCharAt(i, ' '); continue; }
      if (quote != 0) { if (escape) escape = false; else if (c == '\\') escape = true; else if (c == quote) quote = 0; continue; }
      if (c == '\'' || c == '"' || (!sql && c == '`')) { quote = c; continue; }
      if ((!sql && c == '/' && next == '/') || (sql && c == '-' && next == '-')) { out.setCharAt(i, ' '); out.setCharAt(++i, ' '); line = true; }
      else if (c == '/' && next == '*') { out.setCharAt(i, ' '); out.setCharAt(++i, ' '); block = true; }
    }
    return out.toString();
  }

  private String stripLineComment(String value, char marker) {
    StringBuilder out = new StringBuilder(value); char quote = 0; boolean escape = false, comment = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (comment) { if (c == '\n' || c == '\r') comment = false; else out.setCharAt(i, ' '); continue; }
      if (quote != 0) { if (escape) escape = false; else if (c == '\\') escape = true; else if (c == quote) quote = 0; continue; }
      if (c == '\'' || c == '"') quote = c;
      else if (c == marker) { out.setCharAt(i, ' '); comment = true; }
    }
    return out.toString();
  }

  private String stripDelimited(String value, String start, String end) {
    StringBuilder out = new StringBuilder(value); int from = 0;
    while (true) {
      int begin = value.indexOf(start, from); if (begin < 0) break;
      int finish = value.indexOf(end, begin + start.length()); if (finish < 0) finish = value.length() - end.length();
      int limit = Math.min(value.length(), finish + end.length());
      for (int i = begin; i < limit; i++) if (value.charAt(i) != '\n' && value.charAt(i) != '\r') out.setCharAt(i, ' ');
      from = limit;
    }
    return out.toString();
  }

  private int count(String sql, Object... args) { Integer value = jdbc.queryForObject(sql, args, Integer.class); return value == null ? 0 : value; }
  private String sha256(Path file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = Files.newInputStream(file)) { byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read); } StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format("%02x", b)); return out.toString(); }
  private String extension(String path) { int i = path.lastIndexOf('.'); return i < 0 ? "" : path.substring(i + 1).toLowerCase(Locale.ROOT); }
  private String limit(String value, int size) { if (value == null) return "未知错误"; return value.length() <= size ? value : value.substring(0, size); }
  private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
  private String safe(String value) { return value == null ? "" : value; }

  private static class RunContext {
    Long runId, taskId, snapshotId, manifestId, repositoryId, ownerUserId, resultId;
    String ownerUserName, taskType, scanSourceType, application, versionNo, promptSnapshot, serverRootPath;
  }

  private static class ExecutionUnit {
    Long id, runId, manifestFileId, snapshotRuleId; int retryCount;
    String stage, status, preliminaryResult, resultCommitKey, relativePath, contentHash, ruleSnapshot, leaseId;
  }
}
