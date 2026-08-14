package com.scan.center.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan.center.common.AuditOperator;
import com.scan.center.common.GeneratedIds;
import com.scan.center.exception.BusinessException;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI/规则扫描执行引擎。
 *
 * 负责从队列中领取一次扫描运行（{@code scan_task_run}），按执行单元（{@code task_execution_unit}）
 * 逐条驱动规则扫描或大模型扫描，将发现的问题写入 {@code scan_issue}，并持续刷新任务/运行/结果进度。
 *
 *
 * 核心流程：
 *主循环：{@link #execute(Long)} 在 CAS 将运行状态置为 RUNNING 后，循环领取单元、
 *       处理、刷新进度，直至无待处理单元或收到停止请求。
 *并发限制：通过 {@link ModelConfigService#acquireCredential} 获取模型凭证，
 *       {@link #waitForCredential} 轮询等待；单元级 lease 租约（5 分钟）防止重复领取。
 *AI 调用：{@link #scanWithAi} 按扫描源类型（CODE/MD）组装提示词与文件块，
 *       经 {@link AiModelHttpClient} 调用模型并解析 issues 落库。
 *结果汇总：{@link #refreshProgress} / {@link #refreshResult} 汇总单元、文件、
 *       问题及风险等级计数；{@link #finish} 判定最终 SUCCESS/PARTIAL_SUCCESS/FAILED。
 *
 */
@Component
public class ScanTaskExecutor {
  /** 本类日志记录器。 */
  private static final Logger log = LoggerFactory.getLogger(ScanTaskExecutor.class);
  /** 数据库访问模板，用于执行单元领取、进度更新与问题落库。 */
  private final JdbcTemplate jdbc;
  /** JSON 序列化/反序列化工具，用于规则快照、提示词快照与 AI 响应持久化。 */
  private final ObjectMapper json;
  /** 模型配置与凭证池服务，控制并发凭证获取与释放。 */
  private final ModelConfigService modelConfig;
  /** 大模型 HTTP 客户端，发起 AI 扫描请求。 */
  private final AiModelHttpClient aiClient;
  /** Spring 事件发布器，用于重新触发排队中的运行。 */
  private final ApplicationEventPublisher events;

  /**
   * 构造扫描执行引擎。
   *
   * @param jdbc        数据库访问模板
   * @param json        JSON 工具
   * @param modelConfig 模型配置与凭证服务
   * @param aiClient    大模型 HTTP 客户端
   * @param events      事件发布器
   */
  public ScanTaskExecutor(
      JdbcTemplate jdbc,
      ObjectMapper json,
      ModelConfigService modelConfig,
      AiModelHttpClient aiClient,
      ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.json = json;
    this.modelConfig = modelConfig;
    this.aiClient = aiClient;
    this.events = events;
  }

  /**
   * 监听任务入队事件，异步启动一次扫描运行。
   *
   * 与 {@link #execute(Long)} 配合：事件驱动 + {@code @Async} 避免阻塞发布线程。
   *
   *
   * @param event 任务入队事件，携带 {@code runId}
   */
  @Async
  @EventListener
  public void onQueued(TaskService.TaskQueuedEvent event) {
    execute(event.getRunId());
  }

  /**
   * 应用启动完成后恢复中断工作：回收过期租约，并重新发布 QUEUED 运行。
   *
   * 先调用 {@link #recoverExpiredWork()} 清理僵尸 RUNNING 状态，再将库中仍为 QUEUED 的运行
   * 逐一发布 {@link TaskService.TaskQueuedEvent} 以继续执行。
   *
   */
  @EventListener(ApplicationReadyEvent.class)
  public void recoverOnStartup() {
    recoverExpiredWork();
    List<Long> queued = jdbc.query("SELECT id FROM scan_task_run WHERE status='QUEUED' ORDER BY queue_time,id", (rs, row) -> rs.getLong(1));
    for (Long id : queued) events.publishEvent(new TaskService.TaskQueuedEvent(id));
  }

  /**
   * 定时回收过期的工作单元与运行状态（默认每 60 秒执行一次）。
   *
   * 并发/容错：超过 5 分钟未更新的 RUNNING 执行单元重置为 PENDING 并清空 lease；
   * 超时的 RUNNING/STOP_REQUESTED 运行按是否已请求停止恢复为 PAUSED 或重新 QUEUED。
   *
   */
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

  /**
   * 执行一次扫描运行的主入口与主循环。
   *
   * 流程概要：
   *CAS 将 {@code scan_task_run.status} 从 QUEUED 更新为 RUNNING（失败则直接返回，避免重复执行）。
   *加载运行上下文、确保 {@code scan_result} 存在、同步任务状态为 RUNNING。
   *主循环：检测 STOP_REQUESTED → 暂停；否则 {@link #nextUnit} 领取单元 →
   *       {@link #process} 处理 → {@link #refreshProgress} 刷新进度，直至无待处理单元。
   *正常结束调用 {@link #finish}；致命扫描错误抛出 {@link ScanAbortException} 整次失败；
   *       其它异常将运行与任务标记为 FAILED。
   *
   *
   * @param runId 扫描运行 ID（{@code scan_task_run.id}）
   */
  public void execute(Long runId) {
    if (jdbc.update("UPDATE scan_task_run SET status='RUNNING',start_time=COALESCE(start_time,CURRENT_TIMESTAMP),update_time=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'", runId) != 1) return;
    RunContext context = null;
    try {
      context = context(runId);
      jdbc.update("UPDATE scan_task SET status='RUNNING',start_time=COALESCE(start_time,CURRENT_TIMESTAMP),update_time=CURRENT_TIMESTAMP WHERE id=?", context.taskId);
      ensureResult(context);
      log.info("扫描运行开始 runId={}, taskId={}, resultId={}, taskType={}, scanSourceType={}, manifestId={}",
          context.runId, context.taskId, context.resultId, context.taskType, context.scanSourceType, context.manifestId);
      while (true) {
        String runStatus = jdbc.queryForObject("SELECT status FROM scan_task_run WHERE id=?", new Object[] {runId}, String.class);
        if ("STOP_REQUESTED".equals(runStatus)) {
          pause(context); return;
        }
        ExecutionUnit unit = nextUnit(context);
        if (unit == null) break;
        process(context, unit);
        refreshProgress(context);
      }
      finish(context);
    } catch (ScanAbortException e) {
      String message = limit(e.getMessage(), 1800);
      log.error("scan run aborted runId={}, reason={}", runId, message, e);
      abortRemainingUnits(runId, message);
      jdbc.update("UPDATE scan_task_run SET status='FAILED',error_message=?,end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", message, runId);
      if (context != null) {
        refreshProgress(context);
        jdbc.update("UPDATE scan_task SET status='FAILED',error_message=?,end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", message, context.taskId);
      }
    } catch (Exception e) {
      String message = limit(e.getMessage(), 1800);
      log.error("scan run failed runId={}", runId, e);
      jdbc.update("UPDATE scan_task_run SET status='FAILED',error_message=?,end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", message, runId);
      if (context != null) jdbc.update("UPDATE scan_task SET status='FAILED',error_message=?,end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", message, context.taskId);
    }
  }

  /**
   * 领取下一个待处理的执行单元，并通过 lease 租约标记为 RUNNING。
   *
   * AI 任务允许按 {@link ModelConfigService#tokenRetryCount()} 重试 FAILED 单元；
   * 规则任务（NORMAL）不重试。若 CAS 更新 lease 失败（并发竞争），递归重试领取。
   *
   *
   * @param context 当前运行上下文
   * @return 已绑定 lease 的执行单元；无待处理单元时返回 {@code null}
   */
  private ExecutionUnit nextUnit(RunContext context) {
    Long runId = context.runId;
    int maxRetries = "AI".equals(context.taskType) ? modelConfig.tokenRetryCount() : 0;
    List<ExecutionUnit> values = jdbc.query(
        "SELECT u.id,u.run_id,u.manifest_file_id,u.task_snapshot_rule_id,u.stage,u.status,u.preliminary_result,u.retry_count,u.result_commit_key,f.relative_path,f.content_hash,sr.rule_snapshot FROM task_execution_unit u JOIN task_scan_manifest_file f ON f.id=u.manifest_file_id JOIN task_snapshot_rule sr ON sr.id=u.task_snapshot_rule_id WHERE u.run_id=? AND (u.status='PENDING' OR (u.status='FAILED' AND u.retry_count<?)) ORDER BY u.id LIMIT 1",
        new Object[] {runId, maxRetries}, (rs, row) -> unit(rs));
    if (values.isEmpty()) return null;
    ExecutionUnit unit = values.get(0);
    String lease = UUID.randomUUID().toString();
    Timestamp expiry = new Timestamp(System.currentTimeMillis() + 5 * 60 * 1000L);
    int changed = jdbc.update(
        "UPDATE task_execution_unit SET status='RUNNING',lease_id=?,lease_expire_time=?,start_time=COALESCE(start_time,CURRENT_TIMESTAMP),update_time=CURRENT_TIMESTAMP WHERE id=? AND (status='PENDING' OR (status='FAILED' AND retry_count<?))",
        lease, expiry, unit.id, maxRetries);
    if (changed != 1) return nextUnit(context);
    unit.leaseId = lease;
    return unit;
  }

  /**
   * 处理单个执行单元：校验文件、解析内容，按任务类型走规则扫描或 AI 扫描。
   *
   * 成功时将单元置为 SUCCESS；失败时递增 retry_count。若 {@link #isFatalScanError} 判定为
   * 共性/配置/模型协议错误，则抛出 {@link ScanAbortException} 终止整次运行。
   *
   *
   * @param context 运行上下文
   * @param unit    已领取 lease 的执行单元
   */
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
      String message = limit(e.getMessage(), 1800);
      jdbc.update(
          "UPDATE task_execution_unit SET status='FAILED',retry_count=retry_count+1,error_message=?,lease_id=NULL,lease_expire_time=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND lease_id=?",
          message, unit.id, unit.leaseId);
      log.warn("scan execution unit failed runId={}, unitId={}, file={}", context.runId, unit.id, unit.relativePath, e);
      // 仅「共性/配置/模型协议」类错误终止整次运行；单文件解析等局部错误继续后续单元
      if (isFatalScanError(e)) {
        throw new ScanAbortException("共性错误已终止任务：[" + unit.relativePath + "] " + message, e);
      }
    }
  }

  /**
   * 判断是否为应终止整次运行的致命扫描错误。
   *
   * 模型协议/配置类错误：继续扫其它文件无意义，应整次失败。遍历异常链，匹配
   * {@link IllegalArgumentException}、{@link IllegalStateException}、{@link BusinessException}
   * 中的特定消息或错误码。
   *
   *
   * @param error 待判定的异常（含 cause 链）
   * @return {@code true} 表示应中止整次运行
   */
  private boolean isFatalScanError(Throwable error) {
    Throwable current = error;
    while (current != null) {
      String message = current.getMessage() == null ? "" : current.getMessage();
      if (current instanceof IllegalArgumentException
          && (message.contains("issues数组")
              || message.contains("不是合法JSON")
              || message.contains("模型响应为空")
              || message.contains("模型阶段["))) {
        return true;
      }
      if (current instanceof IllegalStateException
          && (message.contains("任务快照缺少提示词")
              || message.contains("等待空闲模型凭证超时")
              || message.contains("运行已请求停止"))) {
        return true;
      }
      if (current instanceof BusinessException) {
        int code = ((BusinessException) current).getCode();
        // 60001 未配置 URL；60002 模型 HTTP 调用失败；60003 类凭证问题
        if (code == 60001 || code == 60002 || code == 60003 || code == 60026 || code == 30026) {
          return true;
        }
        if (message.contains("未配置大模型")
            || message.contains("大模型HTTP调用失败")
            || message.contains("UCID")
            || message.contains("Token")) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }

  /**
   * 规则（NORMAL）扫描：按正则或字面量匹配，逐行查找并插入 {@code scan_issue}。
   *
   * 匹配前会剥离注释（{@link #stripComments}）；单文件最多写入 10000 条问题以防失控。
   *
   *
   * @param context  运行上下文
   * @param unit     执行单元
   * @param rule     规则快照
   * @param original 文件原始文本
   * @throws Exception 规则编译或落库失败时抛出
   */
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

  /**
   * AI 扫描：获取模型凭证、调用大模型、解析 issues 并落库。
   *
   * 并发限制：通过 {@link #waitForCredential} 阻塞等待空闲凭证，处理完毕后在
   * {@code finally} 中 {@link ModelConfigService#releaseCredential} 释放。
   *
   *
   * AI 调用策略：
   *
   *CODE 源：合并 AI_CHECK + AI_RESULT_UPDATE 为单次请求，规则内容去重后拼入 user 消息。
   *MD 源：使用 MD_CHECK 提示词，附带应用/版本与检查规则。
   *
   * 调用详情经 {@link #saveAiResponses} 持久化到单元的 {@code ai_response_json}。
   *
   *
   * @param context 运行上下文
   * @param unit    执行单元
   * @param rule    规则快照
   * @param content 已解析的文件正文
   * @throws Exception 凭证等待、模型调用、JSON 解析或落库失败时抛出
   */
  private void scanWithAi(RunContext context, ExecutionUnit unit, ScanRule rule, String content) throws Exception {
    ModelCredential credential = waitForCredential(context.ownerUserId, context.runId);
    List<Map<String, Object>> calls = new ArrayList<Map<String, Object>>();
    try {
      String response;
      String issuesHint = "必须只输出一个 JSON 对象，且包含 issues 数组；无问题时返回 {\"issues\":[]}。"
          + "issues 元素字段：title,severity(HIGH|MEDIUM|LOW|INFO),startLine,endLine,evidence,description,suggestion。"
          + "title、description（问题说明）、suggestion（整改建议）必须使用简体中文，禁止英文段落；evidence 可保留代码原文。"
          + "用户消息中的代码为 \"行号|原文\" 格式，startLine/endLine 必须严格等于左侧行号，禁止估算或按段落推测。";
      String numberedFile = formatAiFileBlock(unit.relativePath, content);
      if ("CODE".equals(context.scanSourceType)) {
        // CODE：合并 AI_CHECK + AI_RESULT_UPDATE 为单次请求（内置提示词与规则内容去重）
        String systemPrompt = mergeTextParts(
            prompt(context.promptSnapshot, "AI_CHECK"),
            prompt(context.promptSnapshot, "AI_RESULT_UPDATE"),
            issuesHint);
        String checkRule = nullToEmpty(rule.getCheckRuleContent()).trim();
        String updateRule = nullToEmpty(rule.getResultUpdateContent()).trim();
        StringBuilder user = new StringBuilder();
        user.append("【检查规则】\n").append(checkRule);
        if (!blank(updateRule) && !normalizePromptKey(updateRule).equals(normalizePromptKey(checkRule))) {
          user.append("\n\n【结果更新/整理要求】\n").append(updateRule);
        }
        user.append("\n\n").append(numberedFile);
        AiCallResult call = aiClient.invokeDetailed(credential, systemPrompt, user.toString());
        calls.add(toCallRecord("AI_SCAN", call));
        response = normalizeJson(call.getContent());
        jdbc.update("UPDATE task_execution_unit SET preliminary_result=?,checkpoint_data=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND lease_id=?",
            response, "{\"stage\":\"AI_SCAN_COMPLETED\"}", unit.id, unit.leaseId);
        log.info("AI_SCAN 完成(单次合并请求) file={}, responseChars={}", unit.relativePath, response.length());
      } else {
        String mdPrompt = prompt(context.promptSnapshot, "MD_CHECK") + "\n" + issuesHint;
        String request = "【应用】" + safe(context.application) + "\n【版本】" + safe(context.versionNo)
            + "\n【检查规则】\n" + nullToEmpty(rule.getCheckRuleContent()) + "\n\n" + numberedFile;
        AiCallResult mdCall = aiClient.invokeDetailed(credential, mdPrompt, request);
        calls.add(toCallRecord("MD_CHECK", mdCall));
        response = normalizeJson(mdCall.getContent());
      }
      saveAiResponses(unit, context, calls);
      String stageLabel = "MD".equals(context.scanSourceType) ? "MD_CHECK" : "AI_SCAN";
      JsonNode issues = requireIssues(response, stageLabel);
      log.info("AI问题落库准备 file={}, unitId={}, resultId={}, runId={}, issues={}",
          unit.relativePath, unit.id, context.resultId, context.runId,
          issues == null ? -1 : issues.size());
      if (issues == null || !issues.isArray() || issues.size() == 0) {
        log.info("AI返回空issues，本文件不插入scan_issue（故意跳过） file={}, unitId={}, resultId={}, runId={}",
            unit.relativePath, unit.id, context.resultId, context.runId);
      }
      int index = 0;
      int skippedNonObject = 0;
      for (JsonNode issue : issues) {
        if (issue == null || issue.isNull() || !issue.isObject()) {
          skippedNonObject++;
          continue;
        }
        int start = Math.max(1, issue.path("startLine").asInt(issue.path("start_line").asInt(1)));
        int end = Math.max(start, issue.path("endLine").asInt(issue.path("end_line").asInt(start)));
        String risk = issue.path("severity").asText(issue.path("riskLevel").asText(rule.getRiskLevel()));
        if (!Arrays.asList("HIGH", "MEDIUM", "LOW", "INFO").contains(risk)) risk = rule.getRiskLevel();
        String evidence = firstText(issue, "evidence", "matchedContent", "code", "snippet");
        String description = firstText(issue, "description", "issueDescription", "detail", "message");
        String suggestion = firstText(issue, "suggestion", "fix", "advice");
        String title = firstText(issue, "title", "name", "ruleName");
        if (blank(title)) title = rule.getRuleName();
        insertIssue(context, unit, rule, unit.relativePath, start, end,
            limit(evidence, 1000), limit(evidence, 1500),
            limit(blank(description) ? rule.getIssueDescription() : description, 2000),
            limit(blank(suggestion) ? rule.getSuggestion() : suggestion, 2000), index++,
            limit(title, 500), risk);
      }
      log.info("AI问题落库完成 file={}, unitId={}, resultId={}, attemptedInsert={}, skippedNonObject={}, tip={}",
          unit.relativePath, unit.id, context.resultId, index, skippedNonObject,
          index == 0 ? "未写入任何scan_issue" : "已写入scan_issue");
      if (index == 0) {
        log.warn("模型未返回可落库问题 file={}, responseChars={}, tip=空issues=故意不插；若有issues但仍为0则元素非对象被跳过",
            unit.relativePath, response.length());
      }
    } catch (Exception e) {
      if (!calls.isEmpty()) {
        try { saveAiResponses(unit, context, calls); } catch (Exception ignored) { /* keep original error */ }
      }
      throw e;
    } finally {
      modelConfig.releaseCredential(credential);
    }
  }

  /**
   * 合并多段提示词，按规范化文本去重，保留首次出现顺序。
   *
   * @param parts 待合并的提示词片段（可为 {@code null}）
   * @return 去重后以双换行拼接的提示词；无有效片段时返回空串
   */
  private String mergeTextParts(String... parts) {
    List<String> kept = new ArrayList<String>();
    Set<String> seen = new HashSet<String>();
    if (parts != null) {
      for (String part : parts) {
        if (blank(part)) continue;
        String trimmed = part.trim();
        String key = normalizePromptKey(trimmed);
        if (seen.contains(key)) continue;
        seen.add(key);
        kept.add(trimmed);
      }
    }
    if (kept.isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < kept.size(); i++) {
      if (i > 0) out.append("\n\n");
      out.append(kept.get(i));
    }
    return out.toString();
  }

  /**
   * 将提示词规范化为去重键：统一换行、trim、折叠空白。
   *
   * @param value 原始提示词
   * @return 规范化后的键；{@code null} 视为空串
   */
  private String normalizePromptKey(String value) {
    return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim().replaceAll("\\s+", " ");
  }

  /**
   * 将单次 AI 调用结果转为可序列化的调用记录 Map。
   *
   * @param stage 阶段标识（如 AI_SCAN、MD_CHECK）
   * @param call  模型调用详细结果
   * @return 含 stage、字符数、耗时、usage、content 及原始请求/响应的记录
   */
  private Map<String, Object> toCallRecord(String stage, AiCallResult call) {
    Map<String, Object> record = new LinkedHashMap<String, Object>();
    record.put("stage", stage);
    record.put("systemChars", call.getSystemChars());
    record.put("userChars", call.getUserChars());
    record.put("requestChars", call.getRequestChars());
    record.put("responseChars", call.getResponseChars());
    record.put("costMs", call.getCostMs());
    Map<String, Object> usage = new LinkedHashMap<String, Object>();
    // 优先 AI 返回 usage；调用侧已对缺失字段做估算兜底
    usage.put("prompt_tokens", call.getPromptTokens());
    usage.put("completion_tokens", call.getCompletionTokens());
    usage.put("total_tokens", call.getTotalTokens());
    record.put("usage", usage);
    record.put("content", parseJsonOrText(call.getContent()));
    record.put("rawRequest", parseJsonOrText(call.getRawRequest()));
    record.put("rawResponse", parseJsonOrText(call.getRawResponse()));
    return record;
  }

  /**
   * 尝试将字符串解析为 JSON 树；失败则原样返回文本。
   *
   * @param value 待解析字符串
   * @return JSON 节点或原始字符串；{@code null} 输入返回 {@code null}
   */
  private Object parseJsonOrText(String value) {
    if (value == null) return null;
    try {
      return json.readTree(value);
    } catch (Exception e) {
      return value;
    }
  }

  /**
   * 将本单元全部 AI 调用记录持久化到 {@code task_execution_unit.ai_response_json}，
   * 并汇总 request_chars 与 prompt_tokens。
   *
   * @param unit    执行单元
   * @param context 运行上下文
   * @param calls   调用记录列表
   * @throws Exception JSON 序列化或数据库更新失败时抛出
   */
  private void saveAiResponses(ExecutionUnit unit, RunContext context, List<Map<String, Object>> calls) throws Exception {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("taskId", context.taskId);
    payload.put("runId", context.runId);
    payload.put("unitId", unit.id);
    payload.put("file", unit.relativePath);
    payload.put("callCount", calls.size());
    payload.put("calls", calls);
    String text = json.writeValueAsString(payload);
    // 汇总请求报文字符数；请求 token 优先取 AI 返回的 usage.prompt_tokens
    int requestChars = 0, promptTokens = 0;
    for (Map<String, Object> call : calls) {
      Object rc = call.get("requestChars");
      if (rc instanceof Number) requestChars += ((Number) rc).intValue();
      promptTokens += extractPromptTokens(call);
    }
    jdbc.update("UPDATE task_execution_unit SET ai_response_json=?,request_chars=?,prompt_tokens=?,update_time=CURRENT_TIMESTAMP WHERE id=?",
        text, requestChars, promptTokens, unit.id);
  }

  /**
   * 从调用记录取 prompt_tokens：优先 AI usage，再 rawResponse，最后按字符估算兜底。
   *
   * @param call 单次调用记录
   * @return 估算或读取到的 prompt token 数；无法估算时返回 0
   */
  private int extractPromptTokens(Map<String, Object> call) {
    Integer fromUsage = readTokenNumber(call.get("usage"), "prompt_tokens");
    if (fromUsage != null && fromUsage.intValue() > 0) return fromUsage.intValue();
    Object raw = call.get("rawResponse");
    if (raw instanceof Map<?, ?>) {
      Integer nested = readTokenNumber(((Map<?, ?>) raw).get("usage"), "prompt_tokens");
      if (nested != null && nested.intValue() > 0) return nested.intValue();
    }
    if (raw instanceof com.fasterxml.jackson.databind.JsonNode) {
      com.fasterxml.jackson.databind.JsonNode node = (com.fasterxml.jackson.databind.JsonNode) raw;
      com.fasterxml.jackson.databind.JsonNode pt = node.path("usage").path("prompt_tokens");
      if (pt.isNumber() && pt.asInt() > 0) return pt.asInt();
      if (pt.isTextual()) {
        try {
          int v = Integer.parseInt(pt.asText().trim());
          if (v > 0) return v;
        } catch (Exception ignored) {}
      }
    }
    // 兜底：与原先本地估算一致（system+user 字符估算）
    int systemChars = numberOrZero(call.get("systemChars"));
    int userChars = numberOrZero(call.get("userChars"));
    if (systemChars + userChars > 0) return estimatePromptTokensByChars(systemChars, userChars);
    int requestChars = numberOrZero(call.get("requestChars"));
    return requestChars > 0 ? Math.max(1, (int) Math.ceil(requestChars / 4.0)) : 0;
  }

  /**
   * 将对象转为 int，非 Number 时返回 0。
   *
   * @param value 待转换值
   * @return 整数值或 0
   */
  private int numberOrZero(Object value) {
    return value instanceof Number ? ((Number) value).intValue() : 0;
  }

  /**
   * 按字符数近似估算 prompt token 数（中文负载折中约 2 字符/token）。
   *
   * @param systemChars system 消息字符数
   * @param userChars   user 消息字符数
   * @return 估算 token 数；总长为 0 时返回 0
   */
  private int estimatePromptTokensByChars(int systemChars, int userChars) {
    int total = systemChars + userChars;
    if (total <= 0) return 0;
    // 无原文脚本信息时，按偏中文负载取折中：约 2 字符/token
    return Math.max(1, (int) Math.ceil(total / 2.0));
  }

  /**
   * 从 Map 形 usage 对象中读取指定 token 字段。
   *
   * @param holder token 容器（通常为 usage Map）
   * @param field  字段名（如 prompt_tokens）
   * @return 解析到的整数；无效或缺失时返回 {@code null}
   */
  private Integer readTokenNumber(Object holder, String field) {
    if (!(holder instanceof Map<?, ?>)) return null;
    Object value = ((Map<?, ?>) holder).get(field);
    if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
    if (value instanceof String) {
      String text = ((String) value).trim();
      if (text.isEmpty() || "-".equals(text)) return null;
      try { return Integer.valueOf(Integer.parseInt(text)); } catch (NumberFormatException ignored) { return null; }
    }
    return null;
  }

  /**
   * 阻塞等待可用的模型凭证（并发限制入口）。
   *
   * 最多轮询 120 次、每次间隔 1 秒；期间若运行已 STOP_REQUESTED/PAUSED 则立即失败。
   * 超时抛出「等待空闲模型凭证超时」。
   *
   *
   * @param userId 任务所属用户 ID，用于凭证池隔离
   * @param runId  当前运行 ID，用于停止检测
   * @return 已占用的模型凭证
   * @throws InterruptedException 线程睡眠被中断
   */
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

  /**
   * 插入扫描问题（使用规则默认标题与风险等级）。
   *
   * @param context       运行上下文
   * @param unit          执行单元
   * @param rule          规则快照
   * @param path          文件相对路径
   * @param start         起始行号
   * @param end           结束行号
   * @param matched       匹配内容摘要
   * @param sourceContext 上下文原文
   * @param description   问题描述
   * @param suggestion    整改建议
   * @param index         本单元内问题序号（用于 commit key）
   */
  private void insertIssue(RunContext context, ExecutionUnit unit, ScanRule rule, String path,
      int start, int end, String matched, String sourceContext, String description, String suggestion, int index) {
    insertIssue(context, unit, rule, path, start, end, matched, sourceContext, description, suggestion, index, rule.getRuleName(), rule.getRiskLevel());
  }

  /**
   * 插入扫描问题到 {@code scan_issue}，以 {@code result_commit_key} 幂等去重。
   *
   * @param context       运行上下文
   * @param unit          执行单元
   * @param rule          规则快照
   * @param path          文件相对路径
   * @param start         起始行号
   * @param end           结束行号
   * @param matched       匹配内容摘要
   * @param sourceContext 上下文原文
   * @param description   问题描述
   * @param suggestion    整改建议
   * @param index         本单元内问题序号
   * @param title         问题标题
   * @param risk          风险等级（HIGH/MEDIUM/LOW/INFO）
   */
  private void insertIssue(RunContext context, ExecutionUnit unit, ScanRule rule, String path,
      int start, int end, String matched, String sourceContext, String description, String suggestion,
      int index, String title, String risk) {
    String commit = unit.resultCommitKey + ":" + index;
    Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM scan_issue WHERE result_commit_key=?", new Object[] {commit}, Integer.class);
    if (exists != null && exists > 0) {
      log.info("scan_issue已存在，跳过重复插入 commitKey={}, file={}, resultId={}, runId={}",
          commit, path, context.resultId, context.runId);
      return;
    }
    try {
      int rows = jdbc.update(
          "INSERT INTO scan_issue(result_id,task_id,run_id,repository_id,rule_id,execution_unit_id,result_commit_key,title,risk_level,rule_type,file_path,start_line,end_line,matched_content,context_content,issue_description,suggestion,status,operator_user_id,operator_user_name) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?)",
          context.resultId, context.taskId, context.runId, context.repositoryId, rule.getId(), unit.id, commit,
          title, risk, rule.getRuleType(), path, start, end, matched, sourceContext, description, suggestion,
          AuditOperator.userId(), AuditOperator.userName());
      log.info("scan_issue插入{} rows={}, commitKey={}, title={}, risk={}, file={}, line={}-{}, resultId={}, runId={}, unitId={}",
          rows == 1 ? "成功" : "异常", rows, commit, title, risk, path, start, end, context.resultId, context.runId, unit.id);
    } catch (Exception e) {
      log.error("scan_issue插入失败 commitKey={}, file={}, resultId={}, runId={}, unitId={}, err={}",
          commit, path, context.resultId, context.runId, unit.id, e.getMessage(), e);
      throw e;
    }
  }

  /**
   * 刷新运行、任务及扫描结果的进度与统计（结果汇总核心方法）。
   *
   * 统计维度：执行单元（总数/成功/失败）、清单文件（完成/成功/失败）、问题总数；
   * 并调用 {@link #refreshResult} 按风险等级汇总到 {@code scan_result}。
   *
   *
   * @param context 运行上下文
   */
  private void refreshProgress(RunContext context) {
    int maxRetries = "AI".equals(context.taskType) ? modelConfig.tokenRetryCount() : 0;
    int total = count("SELECT COUNT(*) FROM task_execution_unit WHERE run_id=?", context.runId);
    int successUnits = count("SELECT COUNT(*) FROM task_execution_unit WHERE run_id=? AND status='SUCCESS'", context.runId);
    int failedUnits = count("SELECT COUNT(*) FROM task_execution_unit WHERE run_id=? AND status='FAILED' AND retry_count>=?", context.runId, maxRetries);
    int completedFiles = count("SELECT COUNT(*) FROM task_scan_manifest_file f WHERE f.manifest_id=? AND NOT EXISTS(SELECT 1 FROM task_execution_unit u WHERE u.run_id=? AND u.manifest_file_id=f.id AND (u.status IN ('PENDING','RUNNING') OR (u.status='FAILED' AND u.retry_count<?)))", context.manifestId, context.runId, maxRetries);
    int successFiles = count("SELECT COUNT(*) FROM task_scan_manifest_file f WHERE f.manifest_id=? AND NOT EXISTS(SELECT 1 FROM task_execution_unit u WHERE u.run_id=? AND u.manifest_file_id=f.id AND u.status<>'SUCCESS')", context.manifestId, context.runId);
    int failedFiles = Math.max(0, completedFiles - successFiles);
    int issues = count("SELECT COUNT(*) FROM scan_issue WHERE run_id=?", context.runId);
    jdbc.update("UPDATE scan_task_run SET total_units=?,completed_units=?,success_units=?,failed_units=?,issue_count=?,last_checkpoint_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?",
        total, successUnits + failedUnits, successUnits, failedUnits, issues, context.runId);
    jdbc.update("UPDATE scan_task SET completed_files=?,success_files=?,failed_files=?,issue_count=?,update_time=CURRENT_TIMESTAMP WHERE id=?",
        completedFiles, successFiles, failedFiles, issues, context.taskId);
    refreshResult(context, completedFiles, successFiles, failedFiles, issues);
    log.info("进度刷新 runId={}, resultId={}, units={}/{} successUnits={} failedUnits={}, files completed={} success={} failed={}, issues={}",
        context.runId, context.resultId, successUnits + failedUnits, total, successUnits, failedUnits,
        completedFiles, successFiles, failedFiles, issues);
  }

  /**
   * 将文件级统计与按风险等级的问题计数写入 {@code scan_result}。
   *
   * @param context   运行上下文
   * @param completed 已完成扫描的文件数
   * @param success   全部单元成功的文件数
   * @param failed    存在失败单元的文件数
   * @param issues    问题总数
   */
  private void refreshResult(RunContext context, int completed, int success, int failed, int issues) {
    int high = count("SELECT COUNT(*) FROM scan_issue WHERE run_id=? AND risk_level='HIGH'", context.runId);
    int medium = count("SELECT COUNT(*) FROM scan_issue WHERE run_id=? AND risk_level='MEDIUM'", context.runId);
    int low = count("SELECT COUNT(*) FROM scan_issue WHERE run_id=? AND risk_level='LOW'", context.runId);
    int info = count("SELECT COUNT(*) FROM scan_issue WHERE run_id=? AND risk_level='INFO'", context.runId);
    int rows = jdbc.update("UPDATE scan_result SET scanned_files=?,success_files=?,failed_files=?,issue_count=?,high_count=?,medium_count=?,low_count=?,info_count=? WHERE id=?",
        completed, success, failed, issues, high, medium, low, info, context.resultId);
    if (rows != 1) {
      log.error("scan_result更新失败（可能结果行不存在） rows={}, resultId={}, runId={}, taskId={}, issues={}",
          rows, context.resultId, context.runId, context.taskId, issues);
    } else {
      log.info("scan_result已更新 resultId={}, runId={}, scanned={}, success={}, failed={}, issueCount={}, high={}, medium={}, low={}, info={}",
          context.resultId, context.runId, completed, success, failed, issues, high, medium, low, info);
    }
  }

  /**
   * 正常结束一次扫描运行：刷新进度并根据单元成败判定最终状态。
   *
   * 无失败单元 → SUCCESS；全部失败 → FAILED；部分成功 → PARTIAL_SUCCESS。
   *
   *
   * @param context 运行上下文
   */
  private void finish(RunContext context) {
    refreshProgress(context);
    int failed = count("SELECT COUNT(*) FROM task_execution_unit WHERE run_id=? AND status='FAILED'", context.runId);
    int success = count("SELECT COUNT(*) FROM task_execution_unit WHERE run_id=? AND status='SUCCESS'", context.runId);
    int issues = count("SELECT COUNT(*) FROM scan_issue WHERE run_id=?", context.runId);
    Integer resultExists = jdbc.queryForObject("SELECT COUNT(*) FROM scan_result WHERE id=?", new Object[] {context.resultId}, Integer.class);
    String status = failed == 0 ? "SUCCESS" : (success == 0 ? "FAILED" : "PARTIAL_SUCCESS");
    jdbc.update("UPDATE scan_task_run SET status=?,end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", status, context.runId);
    jdbc.update("UPDATE scan_task SET status=?,end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", status, context.taskId);
    log.info("扫描运行结束 status={}, runId={}, taskId={}, resultId={}, resultExists={}, successUnits={}, failedUnits={}, issueRows={}",
        status, context.runId, context.taskId, context.resultId, resultExists, success, failed, issues);
  }

  /**
   * 响应停止请求，将运行与任务状态置为 PAUSED。
   *
   * @param context 运行上下文
   */
  private void pause(RunContext context) {
    jdbc.update("UPDATE scan_task_run SET status='PAUSED',update_time=CURRENT_TIMESTAMP WHERE id=?", context.runId);
    jdbc.update("UPDATE scan_task SET status='PAUSED',update_time=CURRENT_TIMESTAMP WHERE id=?", context.taskId);
  }

  /**
   * 确保当前运行存在 {@code scan_result} 行：已有则复用 ID，否则新建。
   *
   * @param context 运行上下文（成功后会设置 {@code resultId}）
   */
  private void ensureResult(RunContext context) {
    List<Long> ids = jdbc.query("SELECT id FROM scan_result WHERE run_id=?", new Object[] {context.runId}, (rs, row) -> rs.getLong(1));
    if (!ids.isEmpty()) {
      context.resultId = ids.get(0);
      log.info("复用已有scan_result resultId={}, runId={}, taskId={}", context.resultId, context.runId, context.taskId);
      return;
    }
    try {
      KeyHolder key = new GeneratedKeyHolder();
      jdbc.update(connection -> {
        PreparedStatement ps = connection.prepareStatement("INSERT INTO scan_result(task_id,run_id,operator_user_id,operator_user_name) VALUES(?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
        ps.setLong(1, context.taskId); ps.setLong(2, context.runId); ps.setLong(3, context.ownerUserId); ps.setString(4, context.ownerUserName); return ps;
      }, key);
      context.resultId = GeneratedIds.require(key);
      log.info("新建scan_result成功 resultId={}, runId={}, taskId={}（与是否有issue无关，运行开始即创建）",
          context.resultId, context.runId, context.taskId);
    } catch (Exception e) {
      log.error("新建scan_result失败 runId={}, taskId={}, err={}（若含Unique/23505，可能仍存在旧约束 uk_scan_result_task）",
          context.runId, context.taskId, e.getMessage(), e);
      throw e;
    }
  }

  /**
   * 从数据库加载一次扫描运行的完整上下文。
   *
   * @param runId 运行 ID
   * @return 填充完毕的 {@link RunContext}
   */
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

  /**
   * 从 JDBC {@link ResultSet} 映射为 {@link ExecutionUnit}。
   *
   * @param rs 查询结果集（需含单元与关联文件、规则列）
   * @return 执行单元对象
   * @throws SQLException 读取结果集失败时抛出
   */
  private ExecutionUnit unit(ResultSet rs) throws SQLException {
    ExecutionUnit v = new ExecutionUnit(); v.id = rs.getLong("id"); v.runId = rs.getLong("run_id");
    v.manifestFileId = rs.getLong("manifest_file_id"); v.snapshotRuleId = rs.getLong("task_snapshot_rule_id");
    v.stage = rs.getString("stage"); v.status = rs.getString("status"); v.preliminaryResult = rs.getString("preliminary_result");
    v.retryCount = rs.getInt("retry_count"); v.resultCommitKey = rs.getString("result_commit_key");
    v.relativePath = rs.getString("relative_path"); v.contentHash = rs.getString("content_hash"); v.ruleSnapshot = rs.getString("rule_snapshot"); return v;
  }

  /**
   * 从任务快照 JSON 中读取指定类型的提示词内容。
   *
   * @param snapshot 提示词快照 JSON 字符串
   * @param type     提示词类型（如 AI_CHECK、MD_CHECK）
   * @return 提示词正文
   * @throws Exception  JSON 解析失败或缺少提示词时抛出
   */
  private String prompt(String snapshot, String type) throws Exception {
    JsonNode node = json.readTree(snapshot).path(type).path("promptContent");
    if (!node.isTextual() || blank(node.asText())) throw new IllegalStateException("任务快照缺少提示词：" + type);
    return node.asText();
  }

  /**
   * 因致命错误中止时，将剩余 PENDING/RUNNING 单元批量标记为 FAILED。
   *
   * @param runId   运行 ID
   * @param message 终止原因（会截断并前缀说明）
   */
  private void abortRemainingUnits(Long runId, String message) {
    jdbc.update(
        "UPDATE task_execution_unit SET status='FAILED',error_message=?,lease_id=NULL,lease_expire_time=NULL,update_time=CURRENT_TIMESTAMP WHERE run_id=? AND status IN ('PENDING','RUNNING')",
        limit("因其它单元失败已终止：" + message, 1800), runId);
  }

  /**
   * 校验模型响应为合法 JSON 根节点。
   *
   * @param value 模型原始响应文本
   * @param stage 阶段标识（用于错误消息）
   * @return 解析后的 JSON 根节点
   * @throws Exception 响应为空或非 JSON 时抛出 {@link IllegalArgumentException}
   */
  private JsonNode requireJson(String value, String stage) throws Exception {
    try {
      JsonNode root = json.readTree(value);
      if (root == null || root.isMissingNode() || root.isNull()) {
        throw new IllegalArgumentException("模型响应为空");
      }
      return root;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("模型阶段[" + stage + "]返回的不是合法JSON：" + limit(e.getMessage(), 200));
    }
  }

  /**
   * 从模型响应中提取 issues 数组并校验存在性。
   *
   * @param value 模型响应文本
   * @param stage 阶段标识
   * @return issues 数组节点
   * @throws Exception 缺少 issues 数组时抛出 {@link IllegalArgumentException}
   */
  private JsonNode requireIssues(String value, String stage) throws Exception {
    JsonNode issues = extractIssuesArray(value);
    if (issues == null || !issues.isArray()) {
      throw new IllegalArgumentException("模型阶段[" + stage + "]响应缺少issues数组，原始片段：" + limit(value, 300));
    }
    return issues;
  }

  /**
   * 兼容 issues / findings / problems，或根节点直接是数组。
   *
   * @param value 模型响应文本
   * @return 问题数组节点；无法解析时返回 {@code null}
   */
  private JsonNode extractIssuesArray(String value) {
    if (blank(value)) return null;
    try {
      JsonNode root = json.readTree(value);
      if (root == null || root.isNull() || root.isMissingNode()) return null;
      if (root.isArray()) return root;
      for (String key : Arrays.asList("issues", "findings", "problems", "results", "items")) {
        JsonNode node = root.get(key);
        if (node != null && node.isArray()) return node;
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * 从 issue JSON 对象中按候选键顺序取第一个非空文本字段。
   *
   * @param issue 问题 JSON 节点
   * @param keys  候选字段名（按优先级）
   * @return 第一个匹配的非空文本；均无则返回空串
   */
  private String firstText(JsonNode issue, String... keys) {
    for (String key : keys) {
      JsonNode node = issue.get(key);
      if (node != null && node.isTextual() && !blank(node.asText())) return node.asText();
    }
    return "";
  }

  /**
   * {@code null} 安全转为空串。
   *
   * @param value 原始字符串
   * @return 非 null 字符串
   */
  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /**
   * 扫描运行因共性错误而中止时抛出的运行时异常。
   *
   * 任一执行单元遇到 {@link #isFatalScanError} 为 true 的错误时，由 {@link #process} 抛出以终止整次运行。
   *
   */
  private static final class ScanAbortException extends RuntimeException {
    /**
     * @param message 中止原因
     * @param cause   原始异常
     */
    private ScanAbortException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * 规范化模型返回的 JSON 文本：去除 markdown 代码围栏并截取首尾 JSON 对象/数组。
   *
   * @param value 模型原始输出
   * @return  trim 后的 JSON 片段
   */
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

  /**
   * 按文件类型解析磁盘文件为 UTF-8 文本（支持 docx、pdf 及普通文本）。
   *
   * @param path 文件路径
   * @return 文件正文
   * @throws Exception 文件过大（>10MB）、格式不支持或 IO 失败时抛出
   */
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

  /**
   * 按文件扩展名选择注释剥离策略，供规则扫描匹配使用。
   *
   * @param content 原始文件内容
   * @param type    扩展名（小写）
   * @return 注释被空白替换后的内容
   */
  private String stripComments(String content, String type) {
    if (Arrays.asList("xml", "html", "vue").contains(type)) return stripDelimited(content, "<!--", "-->");
    if (Arrays.asList("py", "sh", "yml", "yaml", "properties").contains(type)) return stripLineComment(content, '#');
    if ("sql".equals(type)) return stripCStyle(content, true);
    if (Arrays.asList("java", "js", "ts", "css", "json").contains(type)) return stripCStyle(content, false);
    return content;
  }

  /**
   * 剥离 C 风格（及 SQL 双横线）块注释与行注释，保留字符串字面量内内容。
   *
   * @param value 原始文本
   * @param sql   为 true 时使用 {@code --} 作为行注释（SQL 模式）
   * @return 注释位置被空格替换后的文本
   */
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

  /**
   * 剥离指定字符开头的行注释（如 {@code #}），保留引号内内容。
   *
   * @param value  原始文本
   * @param marker 行注释起始字符
   * @return 注释位置被空格替换后的文本
   */
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

  /**
   * 剥离定界符包裹的注释块（如 HTML {@code <!-- -->}）。
   *
   * @param value 原始文本
   * @param start 注释开始定界符
   * @param end   注释结束定界符
   * @return 注释块内非换行字符被空格替换后的文本
   */
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

  /**
   * 执行 COUNT 查询并返回整数结果。
   *
   * @param sql  COUNT SQL
   * @param args 绑定参数
   * @return 计数；查询为 null 时返回 0
   */
  private int count(String sql, Object... args) { Integer value = jdbc.queryForObject(sql, args, Integer.class); return value == null ? 0 : value; }

  /**
   * 计算文件的 SHA-256 十六进制摘要，用于与清单 content_hash 校验一致性。
   *
   * @param file 文件路径
   * @return 小写 hex 摘要字符串
   * @throws Exception 算法或 IO 失败时抛出
   */
  private String sha256(Path file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = Files.newInputStream(file)) { byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read); } StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format("%02x", b)); return out.toString(); }

  /**
   * 从路径提取小写扩展名（不含点）。
   *
   * @param path 文件路径或文件名
   * @return 扩展名；无扩展名时返回空串
   */
  private String extension(String path) { int i = path.lastIndexOf('.'); return i < 0 ? "" : path.substring(i + 1).toLowerCase(Locale.ROOT); }

  /**
   * 将文件正文格式化为带行号的 Markdown 代码块，便于模型准确回填 startLine/endLine。
   * 统一换行为 \n，每行形如 {@code 行号|原文}。
   *
   * @param relativePath 文件相对路径（用于围栏语言推断与展示）
   * @param content      文件正文
   * @return 供 AI user 消息使用的格式化文本块
   */
  private String formatAiFileBlock(String relativePath, String content) {
    String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    String lang = fenceLanguage(relativePath);
    String fence = normalized.contains("```") ? "````" : "```";
    StringBuilder out = new StringBuilder(normalized.length() + lines.length * 8 + 128);
    out.append("【文件】").append(relativePath == null ? "" : relativePath).append('\n');
    out.append("【说明】以下代码每一行格式为 \"行号|原文\"；issues 的 startLine/endLine 必须严格对应左侧行号，禁止估算。\n");
    out.append(fence).append(lang).append('\n');
    for (int i = 0; i < lines.length; i++) {
      out.append(i + 1).append('|').append(lines[i]).append('\n');
    }
    out.append(fence);
    return out.toString();
  }

  /**
   * 根据文件扩展名推断 Markdown 代码围栏语言标识。
   *
   * @param relativePath 相对路径
   * @return 围栏语言字符串（如 java、yaml）；未知扩展名则用小写扩展名或 text
   */
  private String fenceLanguage(String relativePath) {
    String ext = extension(relativePath);
    if ("java".equals(ext)) return "java";
    if ("xml".equals(ext)) return "xml";
    if ("yml".equals(ext) || "yaml".equals(ext)) return "yaml";
    if ("json".equals(ext)) return "json";
    if ("js".equals(ext)) return "javascript";
    if ("ts".equals(ext)) return "typescript";
    if ("vue".equals(ext)) return "vue";
    if ("sql".equals(ext)) return "sql";
    if ("md".equals(ext) || "markdown".equals(ext)) return "markdown";
    if ("py".equals(ext)) return "python";
    if ("sh".equals(ext) || "bash".equals(ext)) return "bash";
    if ("properties".equals(ext)) return "properties";
    if ("html".equals(ext) || "htm".equals(ext)) return "html";
    if ("css".equals(ext)) return "css";
    if ("txt".equals(ext)) return "text";
    return ext.isEmpty() ? "text" : ext;
  }

  /**
   * 截断字符串至指定最大长度。
   *
   * @param value 原始字符串
   * @param size  最大长度
   * @return 截断后的字符串；{@code null} 返回「未知错误」
   */
  private String limit(String value, int size) { if (value == null) return "未知错误"; return value.length() <= size ? value : value.substring(0, size); }

  /**
   * 判断字符串是否为 null 或空白。
   *
   * @param value 待判定字符串
   * @return {@code true} 表示 null 或 trim 后为空
   */
  private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

  /**
   * null 安全字符串，null 转为空串。
   *
   * @param value 原始字符串
   * @return 非 null 字符串
   */
  private String safe(String value) { return value == null ? "" : value; }

  /**
   * 一次扫描运行的内存上下文，贯穿主循环与各处理阶段。
   */
  private static class RunContext {
    /** 运行/任务/快照/清单/仓库/属主/结果等 ID。 */
    Long runId, taskId, snapshotId, manifestId, repositoryId, ownerUserId, resultId;
    /** 属主用户名、任务类型(NORMAL/AI)、扫描源(CODE/MD)、应用、版本、提示词快照、服务端根路径。 */
    String ownerUserName, taskType, scanSourceType, application, versionNo, promptSnapshot, serverRootPath;
  }

  /**
   * 单个执行单元（文件 × 规则）的运行时视图，含 lease 与清单关联信息。
   */
  private static class ExecutionUnit {
    /** 单元/运行/清单文件/快照规则 ID。 */
    Long id, runId, manifestFileId, snapshotRuleId;
    /** 已重试次数。 */
    int retryCount;
    /** 阶段、状态、AI 中间结果、结果提交键、相对路径、内容哈希、规则快照、租约 ID。 */
    String stage, status, preliminaryResult, resultCommitKey, relativePath, contentHash, ruleSnapshot, leaseId;
  }
}
