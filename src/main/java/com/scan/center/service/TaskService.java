package com.scan.center.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan.center.common.*;
import com.scan.center.dto.TaskCreateDTO;
import com.scan.center.dto.TaskExecuteResult;
import com.scan.center.exception.BusinessException;
import com.scan.center.execution.SourceWorkspaceService;
import com.scan.center.mapper.*;
import com.scan.center.model.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 扫描任务生命周期服务。
 *
 * 负责扫描任务从创建到执行完毕的全流程编排，主要包括：
 *
 *创建/编辑：校验扫描源与规则，生成任务快照（含规则绑定、工作区准备）及扫描清单
 *启动/停止/恢复/放弃：管理运行实例（run）状态流转与执行单元调度
 *分页查询：按关键字、状态、应用名称、版本号等条件检索任务列表
 *
 *
 * @see ScanTask
 * @see TaskSnapshot
 * @see TaskManifest
 */
@Service
public class TaskService {
  /** 系统支持的待扫描文件扩展名集合（不含前导点，小写）。 */
  private static final Set<String> SUPPORTED_TYPES = new LinkedHashSet<String>(Arrays.asList(
      "java", "js", "ts", "vue", "xml", "yml", "yaml", "json", "md", "txt", "sql",
      "properties", "html", "css", "py", "sh", "docx", "pdf"));

  /** 扫描任务数据访问层。 */
  private final TaskMapper mapper;

  /** 扫描规则数据访问层。 */
  private final RuleMapper ruleMapper;

  /** 代码库/扫描源服务。 */
  private final RepositoryService repositoryService;

  /** 大模型配置与运行参数服务。 */
  private final ModelConfigService modelConfigService;

  /** 扫描源工作区（Git 拉取、ZIP 解压等）服务。 */
  private final SourceWorkspaceService workspaceService;

  /** JDBC 模板，用于快照、清单、运行等关联表的直接 SQL 操作。 */
  private final JdbcTemplate jdbc;

  /** JSON 序列化/反序列化工具。 */
  private final ObjectMapper json;

  /** Spring 事件发布器，用于事务提交后触发任务入队。 */
  private final ApplicationEventPublisher publisher;

  /**
   * 构造任务服务，注入所需依赖。
   *
   * @param mapper             任务 Mapper
   * @param ruleMapper         规则 Mapper
   * @param repositoryService  扫描源服务
   * @param modelConfigService 大模型配置服务
   * @param workspaceService   工作区服务
   * @param jdbc               JDBC 模板
   * @param json               ObjectMapper
   * @param publisher          事件发布器
   */
  public TaskService(
      TaskMapper mapper,
      RuleMapper ruleMapper,
      RepositoryService repositoryService,
      ModelConfigService modelConfigService,
      SourceWorkspaceService workspaceService,
      JdbcTemplate jdbc,
      ObjectMapper json,
      ApplicationEventPublisher publisher) {
    this.mapper = mapper;
    this.ruleMapper = ruleMapper;
    this.repositoryService = repositoryService;
    this.modelConfigService = modelConfigService;
    this.workspaceService = workspaceService;
    this.jdbc = jdbc;
    this.json = json;
    this.publisher = publisher;
  }

  /**
   * 分页查询扫描任务列表。
   *
   * 支持多维度筛选：
   *
   *{@code keyword}：任务编号/名称模糊匹配
   *{@code status}：任务状态精确匹配
   *{@code application}：按当前快照关联扫描源的应用名称筛选
   *{@code versionNo}：按当前快照的版本号（6 位数字）筛选
   *
   * 应用与版本筛选基于 {@code task_snapshot.application} / {@code task_snapshot.version_no}，
   * 通过任务当前快照 ID 关联查询。
   *
   * @param keyword     关键字（任务编号或名称，可为空）
   * @param status      任务状态（可为空）
   * @param application 应用名称（可为空）
   * @param versionNo   版本号（可为空）
   * @param page        页码，最小为 1
   * @param size        每页条数，范围 1~100
   * @return 分页结果，含任务列表、当前页码、页大小及总记录数
   */
  public PageResult<ScanTask> page(String keyword, String status, String application, String versionNo, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    return new PageResult<ScanTask>(
        mapper.page(keyword, status, application, versionNo, (p - 1) * s, s), p, s,
        mapper.count(keyword, status, application, versionNo));
  }

  /**
   * 根据 ID 获取扫描任务详情。
   *
   * 若任务存在当前快照，则额外加载快照绑定的规则 ID 列表并填充至 {@link ScanTask#setRuleIds}。
   *
   * @param id 任务 ID
   * @return 任务实体（含 ruleIds）
   * @throws BusinessException 任务不存在（错误码 30001）
   */
  public ScanTask get(Long id) {
    ScanTask value = mapper.findById(id);
    if (value == null) throw new BusinessException(30001, "扫描任务不存在");
    List<Long> ruleIds = new ArrayList<Long>();
    if (value.getCurrentSnapshotId() != null) {
      ruleIds = jdbc.query(
          "SELECT rule_id FROM task_snapshot_rule WHERE task_snapshot_id=? ORDER BY rule_order",
          new Object[] {value.getCurrentSnapshotId()}, (rs, row) -> rs.getLong(1));
    }
    value.setRuleIds(ruleIds);
    return value;
  }

  /**
   * 获取指定任务的全部快照列表，按快照版本号降序排列。
   *
   * @param taskId 任务 ID
   * @return 快照列表
   * @throws BusinessException 任务不存在（错误码 30001）
   */
  public List<TaskSnapshot> snapshots(Long taskId) {
    get(taskId);
    return jdbc.query(
        "SELECT * FROM task_snapshot WHERE task_id=? ORDER BY snapshot_version DESC",
        new Object[] {taskId}, (rs, row) -> snapshot(rs));
  }

  /**
   * 获取指定任务下的单个快照。
   *
   * @param taskId     任务 ID
   * @param snapshotId 快照 ID
   * @return 快照实体
   * @throws BusinessException 任务不存在（30001）或快照不存在（30022）
   */
  public TaskSnapshot snapshot(Long taskId, Long snapshotId) {
    get(taskId);
    List<TaskSnapshot> values = jdbc.query(
        "SELECT * FROM task_snapshot WHERE id=? AND task_id=?",
        new Object[] {snapshotId, taskId}, (rs, row) -> snapshot(rs));
    if (values.isEmpty()) throw new BusinessException(30022, "任务快照不存在");
    return values.get(0);
  }

  /**
   * 获取任务当前快照对应的最新扫描清单。
   *
   * @param taskId 任务 ID
   * @return 最新版本的扫描清单
   * @throws BusinessException 任务不存在（30001）、尚未生成快照（30023）或清单不存在（30024）
   */
  public TaskManifest currentManifest(Long taskId) {
    ScanTask task = get(taskId);
    if (task.getCurrentSnapshotId() == null) throw new BusinessException(30023, "任务尚未生成快照");
    List<TaskManifest> values = jdbc.query(
        "SELECT * FROM task_scan_manifest WHERE task_snapshot_id=? ORDER BY manifest_version DESC LIMIT 1",
        new Object[] {task.getCurrentSnapshotId()}, (rs, row) -> manifest(rs));
    if (values.isEmpty()) throw new BusinessException(30024, "扫描清单不存在");
    return values.get(0);
  }

  /**
   * 分页查询扫描清单中的文件条目。
   *
   * 支持按相对路径关键字、文件类型筛选；返回结果会附加当前运行下的扫描状态汇总。
   *
   * @param manifestId 清单 ID
   * @param taskId     任务 ID（用于关联运行状态，可为 null 则跳过状态 enrichment）
   * @param keyword    相对路径模糊关键字（可为空）
   * @param fileType   文件类型/扩展名（可为空）
   * @param page       页码，最小为 1
   * @param size       每页条数，范围 1~200
   * @return 分页的文件列表
   */
  public PageResult<TaskManifestFile> manifestFiles(Long manifestId, Long taskId, String keyword, String fileType, int page, int size) {
    int p = Math.max(1, page), s = Math.min(200, Math.max(1, size));
    StringBuilder where = new StringBuilder(" WHERE manifest_id=?");
    List<Object> args = new ArrayList<Object>(); args.add(manifestId);
    if (!blank(keyword)) { where.append(" AND relative_path LIKE ?"); args.add("%" + keyword.trim() + "%"); }
    if (!blank(fileType)) { where.append(" AND file_type=?"); args.add(normalizeType(fileType)); }
    Long total = jdbc.queryForObject("SELECT COUNT(*) FROM task_scan_manifest_file" + where, args.toArray(), Long.class);
    args.add(s); args.add((p - 1) * s);
    List<TaskManifestFile> files = jdbc.query(
        "SELECT * FROM task_scan_manifest_file" + where + " ORDER BY relative_path LIMIT ? OFFSET ?",
        args.toArray(), (rs, row) -> manifestFile(rs));
    enrichFileScanStatus(files, taskId);
    return new PageResult<TaskManifestFile>(files, p, s, total == null ? 0 : total);
  }

  /**
   * 按任务当前运行，汇总每个清单文件的扫描状态与是否有 AI 报文。
   *
   * 遍历 {@code task_execution_unit}，将同一 manifest 文件下的多个执行单元聚合为
   * 单一 scanStatus（优先级 RUNNING > PENDING > FAILED > SUCCESS > NONE），
   * 并累计 requestChars、promptTokens。
   *
   * @param files  待 enrichment 的清单文件列表（原地修改）
   * @param taskId 任务 ID；为 null 或无当前运行时不做 enrichment
   */
  private void enrichFileScanStatus(List<TaskManifestFile> files, Long taskId) {
    if (files == null || files.isEmpty()) return;
    for (TaskManifestFile file : files) {
      file.setScanStatus("NONE");
      file.setHasAiResponse(Boolean.FALSE);
      file.setScanErrorMessage(null);
    }
    if (taskId == null) return;
    Long runId = jdbc.queryForObject("SELECT current_run_id FROM scan_task WHERE id=?", new Object[] {taskId}, Long.class);
    if (runId == null) return;

    StringBuilder in = new StringBuilder();
    List<Object> args = new ArrayList<Object>();
    args.add(runId);
    for (int i = 0; i < files.size(); i++) {
      if (i > 0) in.append(',');
      in.append('?');
      args.add(files.get(i).getId());
    }
    List<Map<String, Object>> units = jdbc.query(
        "SELECT manifest_file_id,status,error_message,"
            + "CASE WHEN ai_response_json IS NULL OR TRIM(ai_response_json)='' THEN 0 ELSE 1 END AS has_ai,"
            + "COALESCE(request_chars,0) AS request_chars,COALESCE(prompt_tokens,0) AS prompt_tokens "
            + "FROM task_execution_unit WHERE run_id=? AND manifest_file_id IN (" + in + ")",
        args.toArray(),
        (rs, row) -> {
          Map<String, Object> item = new HashMap<String, Object>();
          item.put("fileId", rs.getLong("manifest_file_id"));
          item.put("status", rs.getString("status"));
          item.put("error", rs.getString("error_message"));
          item.put("hasAi", rs.getInt("has_ai") == 1);
          item.put("requestChars", rs.getInt("request_chars"));
          item.put("promptTokens", rs.getInt("prompt_tokens"));
          return item;
        });

    Map<Long, List<Map<String, Object>>> byFile = new HashMap<Long, List<Map<String, Object>>>();
    for (Map<String, Object> unit : units) {
      Long fileId = (Long) unit.get("fileId");
      List<Map<String, Object>> list = byFile.get(fileId);
      if (list == null) {
        list = new ArrayList<Map<String, Object>>();
        byFile.put(fileId, list);
      }
      list.add(unit);
    }

    for (TaskManifestFile file : files) {
      List<Map<String, Object>> list = byFile.get(file.getId());
      if (list == null || list.isEmpty()) continue;
      boolean hasRunning = false, hasPending = false, hasFailed = false, hasSuccess = false, hasAi = false;
      String err = null;
      int requestChars = 0, promptTokens = 0;
      for (Map<String, Object> unit : list) {
        String status = (String) unit.get("status");
        if ("RUNNING".equals(status)) hasRunning = true;
        else if ("PENDING".equals(status)) hasPending = true;
        else if ("FAILED".equals(status)) {
          hasFailed = true;
          if (err == null) err = (String) unit.get("error");
        } else if ("SUCCESS".equals(status)) hasSuccess = true;
        if (Boolean.TRUE.equals(unit.get("hasAi"))) hasAi = true;
        Object rc = unit.get("requestChars");
        if (rc instanceof Number) requestChars += ((Number) rc).intValue();
        Object pt = unit.get("promptTokens");
        if (pt instanceof Number) promptTokens += ((Number) pt).intValue();
      }
      String scanStatus;
      if (hasRunning) scanStatus = "RUNNING";
      else if (hasPending) scanStatus = "PENDING";
      else if (hasFailed) scanStatus = "FAILED";
      else if (hasSuccess) scanStatus = "SUCCESS";
      else scanStatus = "NONE";
      file.setScanStatus(scanStatus);
      file.setHasAiResponse(hasAi);
      file.setScanErrorMessage(err);
      file.setRequestChars(requestChars);
      file.setPromptTokens(promptTokens);
    }
  }

  /**
   * 查询某清单文件在当前运行中的 AI 完整返回报文（JSON）。
   *
   * 返回该文件下所有执行单元的 stage、status、ruleId 及 aiResponse（含 UTF-8 乱码修复）。
   *
   * @param taskId         任务 ID
   * @param manifestFileId 清单文件 ID
   * @return 含 taskId、runId、relativePath、units 等字段的 Map
   * @throws BusinessException 任务不存在（30001）、尚未执行（30042）或文件不属于当前运行（30043）
   */
  public Map<String, Object> fileAiResponses(Long taskId, Long manifestFileId) {
    ScanTask task = get(taskId);
    if (task.getCurrentRunId() == null) throw new BusinessException(30042, "任务尚未执行，无 AI 报文");
    Integer exists = jdbc.queryForObject(
        "SELECT COUNT(*) FROM task_scan_manifest_file f JOIN task_scan_manifest m ON m.id=f.manifest_id JOIN scan_task_run r ON r.manifest_id=m.id WHERE f.id=? AND r.id=? AND r.task_id=?",
        new Object[] {manifestFileId, task.getCurrentRunId(), taskId}, Integer.class);
    if (exists == null || exists == 0) throw new BusinessException(30043, "清单文件不存在或不属于当前任务运行");

    List<Map<String, Object>> units = jdbc.query(
        "SELECT u.id,u.stage,u.status,u.ai_response_json,u.error_message,u.finish_time,sr.rule_id FROM task_execution_unit u "
            + "LEFT JOIN task_snapshot_rule sr ON sr.id=u.task_snapshot_rule_id "
            + "WHERE u.run_id=? AND u.manifest_file_id=? ORDER BY u.id",
        new Object[] {task.getCurrentRunId(), manifestFileId},
        (rs, row) -> {
          Map<String, Object> item = new LinkedHashMap<String, Object>();
          item.put("unitId", rs.getLong("id"));
          item.put("stage", rs.getString("stage"));
          item.put("status", rs.getString("status"));
          item.put("ruleId", rs.getObject("rule_id"));
          item.put("errorMessage", rs.getString("error_message"));
          item.put("finishTime", rs.getTimestamp("finish_time"));
          String raw = rs.getString("ai_response_json");
          if (raw == null || raw.trim().isEmpty()) {
            item.put("aiResponse", null);
          } else {
            try {
              item.put("aiResponse", repairUtf8Mojibake(json.readValue(raw, Object.class)));
            } catch (Exception e) {
              item.put("aiResponse", repairUtf8Mojibake(raw));
            }
          }
          return item;
        });

    String path = jdbc.queryForObject(
        "SELECT relative_path FROM task_scan_manifest_file WHERE id=?", new Object[] {manifestFileId}, String.class);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("taskId", taskId);
    result.put("runId", task.getCurrentRunId());
    result.put("manifestFileId", manifestFileId);
    result.put("relativePath", path);
    result.put("units", units);
    return result;
  }

  /**
   * 创建扫描任务。
   *
   * 完整流程：校验 DTO → 插入任务主记录 → 调用 {@link #createSnapshotAndManifest} 生成快照与清单。
   * 若 {@code executeImmediately=true} 且非定时任务，则创建完成后立即 {@link #execute}。
   *
   * @param dto 任务创建参数（名称、扫描源、规则、路径、文件类型等）
   * @return 新任务 ID
   * @throws BusinessException 校验失败、快照/清单生成失败等业务异常
   */
  @Transactional(rollbackFor = Exception.class)
  public Long create(TaskCreateDTO dto) {
    ValidatedTask validated = validate(dto);
    ScanTask task = new ScanTask();
    task.setTaskNo("SCAN" + System.currentTimeMillis());
    task.setTaskName(dto.getTaskName().trim());
    task.setDescription(dto.getDescription());
    task.setRepositoryId(dto.getRepositoryId());
    task.setTaskType(validated.type);
    task.setScopeJson(scopeJson(dto, validated.repository));
    task.setStatus("DRAFT");
    task.setScheduleType(normalizeScheduleType(dto.getScheduleType()));
    task.setScheduleTime(dto.getScheduleTime());
    task.setManifestStatus("BUILDING");
    task.setOwnerUserId(AuditOperator.userId());
    task.setOwnerUserName(AuditOperator.userName());
    task.setOperatorUserId(AuditOperator.userId());
    task.setOperatorUserName(AuditOperator.userName());
    modelConfigService.ensureAiScheduleWindow(validated.type, task.getScheduleType(), task.getScheduleTime());
    mapper.insert(task);
    createSnapshotAndManifest(task, dto, validated);
    if (Boolean.TRUE.equals(dto.getExecuteImmediately())
        && !"ONCE".equals(task.getScheduleType())) execute(task.getId());
    return task.getId();
  }

  /**
   * 编辑扫描任务。
   *
   * 要求当前无进行中的运行；更新主记录后重新调用 {@link #createSnapshotAndManifest}，
   * 生成新版本快照及对应清单，并将 {@code current_snapshot_id} 指向新快照。
   *
   * @param id  任务 ID
   * @param dto 更新后的任务参数
   * @throws BusinessException 任务不存在、存在未结束运行、校验或快照生成失败
   */
  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, TaskCreateDTO dto) {
    ScanTask task = get(id);
    ensureNoOpenRun(id);
    ValidatedTask validated = validate(dto);
    String scheduleType = normalizeScheduleType(dto.getScheduleType());
    modelConfigService.ensureAiScheduleWindow(validated.type, scheduleType, dto.getScheduleTime());
    jdbc.update(
        "UPDATE scan_task SET task_name=?,description=?,repository_id=?,task_type=?,scope_json=?,schedule_type=?,schedule_time=?,manifest_status='BUILDING',operator_user_id=?,operator_user_name=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=FALSE",
        dto.getTaskName().trim(), dto.getDescription(), dto.getRepositoryId(), validated.type,
        scopeJson(dto, validated.repository), scheduleType, dto.getScheduleTime(),
        AuditOperator.userId(), AuditOperator.userName(), id);
    task.setTaskName(dto.getTaskName().trim()); task.setDescription(dto.getDescription());
    task.setRepositoryId(dto.getRepositoryId()); task.setTaskType(validated.type);
    task.setScheduleType(scheduleType); task.setScheduleTime(dto.getScheduleTime());
    createSnapshotAndManifest(task, dto, validated);
  }

  /**
   * 复制已有任务为新任务（名称追加「-副本」）。
   *
   * 从当前快照读取扫描源、规则、路径、文件类型、排除路径及版本号，调用 {@link #create} 创建。
   *
   * @param id 源任务 ID
   * @return 新任务 ID
   * @throws BusinessException 源任务不存在或创建过程失败
   */
  @Transactional(rollbackFor = Exception.class)
  public Long copy(Long id) {
    ScanTask task = get(id);
    TaskSnapshot snapshot = snapshot(id, task.getCurrentSnapshotId());
    List<Long> ruleIds = jdbc.query(
        "SELECT rule_id FROM task_snapshot_rule WHERE task_snapshot_id=? ORDER BY rule_order",
        new Object[] {snapshot.getId()}, (rs, row) -> rs.getLong(1));
    TaskCreateDTO dto = new TaskCreateDTO();
    dto.setTaskName(task.getTaskName() + "-副本"); dto.setDescription(task.getDescription());
    dto.setRepositoryId(snapshot.getRepositoryId()); dto.setRuleIds(ruleIds);
    dto.setScanPaths(readList(snapshot.getScanPaths())); dto.setFileTypes(readList(snapshot.getFileTypes()));
    dto.setExcludePaths(readList(snapshot.getExcludePaths())); dto.setVersionNo(snapshot.getVersionNo());
    dto.setExecuteImmediately(false);
    return create(dto);
  }

  /**
   * 基于当前快照重新生成扫描清单（不新建快照版本）。
   *
   * 适用于工作区文件变更后需刷新清单但快照配置未变的场景。
   *
   * @param taskId 任务 ID
   * @return 新清单 ID
   * @throws BusinessException 任务不存在、存在未结束运行或清单生成失败
   */
  @Transactional(rollbackFor = Exception.class)
  public Long regenerateManifest(Long taskId) {
    ScanTask task = get(taskId);
    ensureNoOpenRun(taskId);
    TaskSnapshot snapshot = snapshot(taskId, task.getCurrentSnapshotId());
    return generateManifest(task, snapshot, readList(snapshot.getScanPaths()), readList(snapshot.getFileTypes()), readList(snapshot.getExcludePaths()),
        countSnapshotRules(snapshot.getId()));
  }

  /**
   * 启动扫描任务执行。
   *
   * 核心流程：
   *校验无进行中的运行
   *Git 扫描源：启动前比对远端 tip，有更新则增量同步并重建清单（不新建快照版本）
   *校验当前清单状态为 READY 且文件摘要未失效（{@link #verifyManifest}）
   *AI 任务：校验 UCID/Token 凭证及并发上限
   *插入 run 记录、批量创建 execution unit、更新任务状态为 QUEUED
   *事务提交后发布 {@link TaskQueuedEvent} 触发异步调度
   *
   * @param id 任务 ID
   * @return 执行结果，含是否因 Git 更新而重建清单及提示信息
   * @throws BusinessException 清单未就绪（30025）、AI 凭证不足（30026）、并发达上限（30042）、
   *                           清单失效（30031）等
   */
  @Transactional(rollbackFor = Exception.class)
  public TaskExecuteResult execute(Long id) {
    ScanTask task = get(id);
    ensureNoOpenRun(id);
    TaskSnapshot snapshot = snapshot(id, task.getCurrentSnapshotId());
    // GIT：启动前比对远端 tip，有更新则增量同步并重建清单；无更新则沿用原快照
    boolean gitUpdated = syncGitBeforeExecute(task, snapshot);
    snapshot = snapshot(id, task.getCurrentSnapshotId());
    TaskManifest manifest = currentManifest(id);
    if (!"READY".equals(manifest.getStatus())) throw new BusinessException(30025, "扫描清单尚未就绪");
    verifyManifest(snapshot, manifest);
    if ("AI".equals(snapshot.getTaskType())
        && modelConfigService.availableCredentialCount(task.getOwnerUserId()) == 0)
      throw new BusinessException(30026, "AI任务至少需要一条启用且有效的UCID/Token");
    ensureAiScanConcurrency(snapshot.getTaskType());
    Long runId = insertRun(task, snapshot, manifest);
    int units = createExecutionUnits(runId, manifest.getId(), snapshot.getId(), snapshot.getTaskType(), snapshot.getScanSourceType());
    jdbc.update("UPDATE scan_task_run SET total_units=? WHERE id=?", units, runId);
    jdbc.update(
        "UPDATE scan_task SET current_run_id=?,status='QUEUED',cancel_requested=FALSE,total_files=?,completed_files=0,success_files=0,failed_files=0,issue_count=0,error_message=NULL,start_time=NULL,end_time=NULL,schedule_type='NONE',schedule_time=NULL,update_time=CURRENT_TIMESTAMP WHERE id=?",
        runId, manifest.getFileCount(), id);
    publishAfterCommit(runId);
    if (gitUpdated) {
      return TaskExecuteResult.of(true,
          "发现代码库更新，已重新拉取最新代码并重建清单（未新建任务快照版本），扫描已启动");
    }
    return TaskExecuteResult.of(false, "扫描已启动");
  }

  /**
   * 校验 AI 扫描任务的服务器级并发上限。
   *
   * @param taskType 任务类型（NORMAL / AI）
   * @throws BusinessException 当前活跃 AI 任务数已达上限（30042）
   */
  private void ensureAiScanConcurrency(String taskType) {
    if (!"AI".equals(taskType)) return;
    int limit = modelConfigService.aiScanTaskConcurrency();
    int active = modelConfigService.activeAiScanTaskCount();
    if (active >= limit) {
      throw new BusinessException(30042,
          "当前服务器AI扫描任务并发已达上限（" + active + "/" + limit
              + "）。请等待已有任务完成后再发起，或联系管理员在「大模型配置 → 运行参数」中调高并发数量。");
    }
  }

  /**
   * 启动前 Git 增量同步；有代码更新时在同一 snapshot 工作区上 fetch，并新建清单版本。
   *
   * @param task     扫描任务
   * @param snapshot 当前任务快照
   * @return {@code true} 表示发生了同步与清单重建
   */
  private boolean syncGitBeforeExecute(ScanTask task, TaskSnapshot snapshot) {
    if (snapshot == null || snapshot.getRepositoryId() == null) return false;
    if (!"CODE".equals(snapshot.getScanSourceType())) return false;
    if (blank(snapshot.getServerRootPath())) return false;
    CodeRepository repository = repositoryService.getInternal(snapshot.getRepositoryId());
    if (!"GIT".equals(repository.getSourceType())) return false;
    Path root = Paths.get(snapshot.getServerRootPath()).toAbsolutePath().normalize();
    boolean updated = workspaceService.syncGitIfNeeded(root, repository);
    if (!updated) return false;
    Long manifestId = generateManifest(task, snapshot,
        readList(snapshot.getScanPaths()), readList(snapshot.getFileTypes()), readList(snapshot.getExcludePaths()),
        countSnapshotRules(snapshot.getId()));
    jdbc.update(
        "UPDATE scan_task SET manifest_status='READY',manifest_file_count=(SELECT file_count FROM task_scan_manifest WHERE id=?),update_time=CURRENT_TIMESTAMP WHERE id=?",
        manifestId, task.getId());
    return true;
  }

  /**
   * 停止正在排队或运行中的任务。
   *
   * QUEUED 状态直接置为 PAUSED；RUNNING 状态置为 STOP_REQUESTED，由执行器优雅停止。
   *
   * @param taskId 任务 ID
   * @throws BusinessException 无可停止的运行（30006）或当前状态不允许停止（30006）
   */
  @Transactional(rollbackFor = Exception.class)
  public void stop(Long taskId) {
    ScanTask task = get(taskId);
    if (task.getCurrentRunId() == null) throw new BusinessException(30006, "当前任务没有可停止的运行");
    int queued = jdbc.update("UPDATE scan_task_run SET status='PAUSED',stop_requested=TRUE,update_time=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'", task.getCurrentRunId());
    int running = queued == 0 ? jdbc.update("UPDATE scan_task_run SET status='STOP_REQUESTED',stop_requested=TRUE,update_time=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'", task.getCurrentRunId()) : 0;
    if (queued + running == 0) throw new BusinessException(30006, "当前任务状态不允许停止");
    jdbc.update("UPDATE scan_task SET status=?,cancel_requested=TRUE,update_time=CURRENT_TIMESTAMP WHERE id=?", queued == 1 ? "PAUSED" : "STOP_REQUESTED", taskId);
  }

  /**
   * 恢复已暂停的任务运行。
   *
   * 将 run 及任务状态重置为 QUEUED，事务提交后重新发布入队事件。
   *
   * @param taskId 任务 ID
   * @throws BusinessException 无可继续的运行（30027）、AI 凭证不足（30026）、并发达上限（30042）
   *                           或 run 非 PAUSED 状态（30027）
   */
  @Transactional(rollbackFor = Exception.class)
  public void resume(Long taskId) {
    ScanTask task = get(taskId);
    if (task.getCurrentRunId() == null) throw new BusinessException(30027, "当前任务没有可继续的运行");
    if ("AI".equals(task.getTaskType())
        && modelConfigService.availableCredentialCount(task.getOwnerUserId()) == 0)
      throw new BusinessException(30026, "继续任务前请启用至少一条有效UCID/Token");
    ensureAiScanConcurrency(task.getTaskType());
    if (jdbc.update("UPDATE scan_task_run SET status='QUEUED',stop_requested=FALSE,resume_count=resume_count+1,error_message=NULL,queue_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=? AND status='PAUSED'", task.getCurrentRunId()) != 1)
      throw new BusinessException(30027, "只有暂停运行可以继续");
    jdbc.update("UPDATE scan_task SET status='QUEUED',cancel_requested=FALSE,error_message=NULL,update_time=CURRENT_TIMESTAMP WHERE id=?", taskId);
    publishAfterCommit(task.getCurrentRunId());
  }

  /**
   * 放弃已暂停的任务运行（不可恢复）。
   *
   * 将 run 及任务状态置为 ABORTED，并记录结束时间。
   *
   * @param taskId 任务 ID
   * @throws BusinessException 当前 run 非 PAUSED 状态，无法放弃（30028）
   */
  @Transactional(rollbackFor = Exception.class)
  public void abort(Long taskId) {
    ScanTask task = get(taskId);
    if (task.getCurrentRunId() == null || jdbc.update("UPDATE scan_task_run SET status='ABORTED',end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=? AND status='PAUSED'", task.getCurrentRunId()) != 1)
      throw new BusinessException(30028, "只有暂停运行可以放弃");
    jdbc.update("UPDATE scan_task SET status='ABORTED',end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", taskId);
  }

  /**
   * 逻辑删除扫描任务。
   *
   * @param id 任务 ID
   * @throws BusinessException 任务不存在或存在未结束运行（30034）
   */
  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    get(id); ensureNoOpenRun(id);
    jdbc.update("UPDATE scan_task SET deleted=TRUE,status='DELETED',update_time=CURRENT_TIMESTAMP WHERE id=?", id);
  }

  /**
   * 创建任务快照并生成扫描清单（create/update 共用核心逻辑）。
   *
   * 步骤：
   *递增快照版本号，采集 prompt 快照，归一化路径/类型/排除项
   *计算快照内容 SHA-256 摘要并 {@link #insertSnapshot}
   *绑定规则至 {@code task_snapshot_rule}
   *准备工作区（Git clone / ZIP 解压等），回写 {@code server_root_path}
   *调用 {@link #generateManifest} 遍历工作区生成文件清单
   *更新任务的 {@code current_snapshot_id}、manifest_status、status
   * 失败时将任务 manifest_status 置为 FAILED 并回滚事务。
   *
   * @param task      任务实体（需已持久化，含 id）
   * @param dto       创建/编辑 DTO
   * @param validated 已通过 {@link #validate} 的校验结果
   */
  private void createSnapshotAndManifest(ScanTask task, TaskCreateDTO dto, ValidatedTask validated) {
    int version = nextSnapshotVersion(task.getId());
    Map<String, Object> prompts = promptSnapshot(validated.type, validated.repository.getScanSourceType());
    List<String> paths = normalizedPaths(dto.getScanPaths());
    List<String> types = normalizedTypes(dto.getFileTypes(), null);
    List<String> excludes = normalizedExcludes(dto.getExcludePaths(), null);
    Map<String, Object> hashContent = new LinkedHashMap<String, Object>();
    hashContent.put("taskType", validated.type); hashContent.put("scanSourceType", validated.repository.getScanSourceType());
    hashContent.put("repositoryId", validated.repository.getId());
    hashContent.put("version", dto.getVersionNo()); hashContent.put("paths", paths); hashContent.put("types", types);
    hashContent.put("excludes", excludes); hashContent.put("rules", dto.getRuleIds()); hashContent.put("prompts", prompts);
    Long snapshotId = insertSnapshot(task, dto, validated, version, paths, types, excludes, prompts, sha256Json(hashContent));
    int order = 0;
    try {
      for (ScanRule rule : validated.rules)
        jdbc.update("INSERT INTO task_snapshot_rule(task_snapshot_id,rule_id,rule_type,rule_order,rule_snapshot) VALUES(?,?,?,?,?)",
            snapshotId, rule.getId(), rule.getRuleType(), order++, json.writeValueAsString(rule));
      Path root = workspaceService.prepare(snapshotId, validated.repository, dto.getVersionNo());
      jdbc.update("UPDATE task_snapshot SET server_root_path=? WHERE id=?", root.toString(), snapshotId);
      TaskSnapshot snapshot = snapshot(task.getId(), snapshotId);
      Long manifestId = generateManifest(task, snapshot, paths, types, excludes, validated.rules.size());
      String finalStatus = "ONCE".equals(task.getScheduleType()) ? "SCHEDULED" : "READY";
      jdbc.update("UPDATE scan_task SET current_snapshot_id=?,manifest_status='READY',status=?,manifest_file_count=(SELECT file_count FROM task_scan_manifest WHERE id=?),update_time=CURRENT_TIMESTAMP WHERE id=?", snapshotId, finalStatus, manifestId, task.getId());
    } catch (BusinessException e) {
      jdbc.update("UPDATE scan_task SET manifest_status='FAILED',status='DRAFT',error_message=? WHERE id=?", limit(e.getMessage(), 1800), task.getId());
      throw e;
    } catch (Exception e) {
      throw new BusinessException(30004, "任务快照生成失败：" + limit(e.getMessage(), 1000));
    }
  }

  /**
   * 生成扫描清单：遍历快照工作区，收集符合路径/类型/排除规则的文件并持久化。
   *
   * 对每个匹配文件计算 SHA-256 内容摘要，写入 {@code task_scan_manifest_file}；
   * 汇总 file_count、excluded_count、total_size 及整清单 manifest_hash。
   * 同一快照可存在多个 manifest_version（如 Git 增量同步后重建）。
   *
   * @param task       扫描任务
   * @param snapshot   任务快照（含 server_root_path）
   * @param paths      归一化后的扫描相对路径列表
   * @param types      允许的文件扩展名列表
   * @param excludes   排除路径片段列表
   * @param ruleCount  适用规则数（写入 applicable_rule_count）
   * @return 新清单 ID
   * @throws BusinessException 路径不存在或越界（30029）、清单生成失败（30030）
   */
  private Long generateManifest(ScanTask task, TaskSnapshot snapshot, List<String> paths, List<String> types, List<String> excludes, int ruleCount) {
    int version = jdbc.queryForObject("SELECT COALESCE(MAX(manifest_version),0)+1 FROM task_scan_manifest WHERE task_snapshot_id=?", new Object[] {snapshot.getId()}, Integer.class);
    Long manifestId = insertManifest(snapshot.getId(), version);
    Path root = Paths.get(snapshot.getServerRootPath()).toAbsolutePath().normalize();
    Set<Path> discovered = new TreeSet<Path>(Comparator.comparing(Path::toString));
    int[] excluded = {0}; long[] totalSize = {0};
    try {
      for (String configured : paths) {
        Path start = ".".equals(configured) ? root : root.resolve(configured).normalize();
        if (!start.startsWith(root) || !Files.exists(start)) throw new BusinessException(30029, "扫描路径不存在或越界：" + configured);
        try (Stream<Path> stream = Files.walk(start)) {
          stream.filter(Files::isRegularFile).forEach(file -> {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String type = extension(relative);
            if (excluded(relative, excludes) || !types.contains(type) || !SUPPORTED_TYPES.contains(type)) excluded[0]++;
            else discovered.add(file.toAbsolutePath().normalize());
          });
        }
      }
      MessageDigest manifestDigest = MessageDigest.getInstance("SHA-256");
      for (Path file : discovered) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        long size = Files.size(file); String hash = sha256(file); totalSize[0] += size;
        manifestDigest.update((relative + ":" + hash + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO task_scan_manifest_file(manifest_id,relative_path,file_type,file_size,last_modified_time,content_hash,applicable_rule_count) VALUES(?,?,?,?,?,?,?)",
            manifestId, relative, extension(relative), size, new Timestamp(Files.getLastModifiedTime(file).toMillis()), hash, ruleCount);
      }
      String manifestHash = hex(manifestDigest.digest());
      jdbc.update("UPDATE task_scan_manifest SET status='READY',file_count=?,excluded_count=?,total_size=?,exclude_summary=?,manifest_hash=?,finish_time=CURRENT_TIMESTAMP WHERE id=?",
          discovered.size(), excluded[0], totalSize[0], "{\"filtered\":" + excluded[0] + "}", manifestHash, manifestId);
      return manifestId;
    } catch (BusinessException e) {
      jdbc.update("UPDATE task_scan_manifest SET status='FAILED',error_message=?,finish_time=CURRENT_TIMESTAMP WHERE id=?", limit(e.getMessage(), 1800), manifestId);
      throw e;
    } catch (Exception e) {
      jdbc.update("UPDATE task_scan_manifest SET status='FAILED',error_message=?,finish_time=CURRENT_TIMESTAMP WHERE id=?", limit(e.getMessage(), 1800), manifestId);
      throw new BusinessException(30030, "扫描清单生成失败：" + limit(e.getMessage(), 1000));
    }
  }

  /**
   * 启动前校验清单有效性：逐文件核对存在性与内容摘要，并重算整清单 hash。
   *
   * @param snapshot 任务快照
   * @param manifest 待校验清单
   * @throws BusinessException 文件缺失/摘要变化或整清单 hash 不匹配（30031）
   */
  private void verifyManifest(TaskSnapshot snapshot, TaskManifest manifest) {
    Path root = Paths.get(snapshot.getServerRootPath()).toAbsolutePath().normalize();
    List<TaskManifestFile> files = jdbc.query("SELECT * FROM task_scan_manifest_file WHERE manifest_id=? ORDER BY relative_path", new Object[] {manifest.getId()}, (rs, row) -> manifestFile(rs));
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (TaskManifestFile file : files) {
        Path path = root.resolve(file.getRelativePath()).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path) || !file.getContentHash().equals(sha256(path))) {
          jdbc.update("UPDATE task_scan_manifest SET status='INVALID',error_message='文件缺失或内容摘要已变化' WHERE id=?", manifest.getId());
          throw new BusinessException(30031, "扫描清单已失效，请重新生成任务快照或清单");
        }
        digest.update((file.getRelativePath() + ":" + file.getContentHash() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      if (!hex(digest.digest()).equals(manifest.getManifestHash())) throw new BusinessException(30031, "扫描清单摘要校验失败");
    } catch (BusinessException e) { throw e; }
    catch (Exception e) { throw new BusinessException(30031, "扫描清单校验失败：" + limit(e.getMessage(), 500)); }
  }

  /**
   * 插入一次扫描运行（run）记录。
   *
   * @param task     扫描任务
   * @param snapshot 关联快照
   * @param manifest 关联清单
   * @return 新 run ID
   */
  private Long insertRun(ScanTask task, TaskSnapshot snapshot, TaskManifest manifest) {
    KeyHolder key = new GeneratedKeyHolder();
    String no = "RUN" + System.currentTimeMillis() + String.format("%03d", new Random().nextInt(1000));
    jdbc.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO scan_task_run(run_no,task_id,task_snapshot_id,manifest_id,status,stop_requested,started_by_user_id,started_by_user_name,queue_time) VALUES(?,?,?,?, 'QUEUED',FALSE,?,?,CURRENT_TIMESTAMP)", Statement.RETURN_GENERATED_KEYS);
      ps.setString(1, no); ps.setLong(2, task.getId()); ps.setLong(3, snapshot.getId()); ps.setLong(4, manifest.getId());
      ps.setLong(5, AuditOperator.userId()); ps.setString(6, AuditOperator.userName()); return ps;
    }, key);
    return GeneratedIds.require(key);
  }

  /**
   * 为一次 run 批量创建执行单元（文件 × 规则 笛卡尔积）。
   *
   * @param runId          运行 ID
   * @param manifestId     清单 ID
   * @param snapshotId     快照 ID
   * @param type           任务类型（NORMAL / AI）
   * @param scanSourceType 扫描源类型（CODE / MD 等）
   * @return 创建的 execution unit 总数
   */
  private int createExecutionUnits(Long runId, Long manifestId, Long snapshotId, String type, String scanSourceType) {
    List<Long> files = jdbc.query("SELECT id FROM task_scan_manifest_file WHERE manifest_id=? ORDER BY id", new Object[] {manifestId}, (rs, row) -> rs.getLong(1));
    List<Long> rules = jdbc.query("SELECT id FROM task_snapshot_rule WHERE task_snapshot_id=? ORDER BY rule_order", new Object[] {snapshotId}, (rs, row) -> rs.getLong(1));
    String stage = "NORMAL".equals(type) ? "NORMAL_MATCH" : ("MD".equals(scanSourceType) ? "MD_CHECK" : "AI_CHECK");
    int count = 0;
    for (Long file : files) for (Long rule : rules) {
      String key = sha256(runId + ":" + file + ":" + rule + ":0:" + stage);
      jdbc.update("INSERT INTO task_execution_unit(run_id,manifest_file_id,task_snapshot_rule_id,segment_no,stage,status,result_commit_key) VALUES(?,?,?,?,?,'PENDING',?)",
          runId, file, rule, 0, stage, key); count++;
    }
    return count;
  }

  /**
   * 校验任务创建/编辑 DTO：扫描源、规则合法性及版本号推导。
   *
   * MD 扫描源强制 AI 规则并取扫描源 versionNo；CODE 扫描源若配置了 6 位版本号则写入快照。
   *
   * @param dto 创建/编辑 DTO
   * @return 校验通过后的封装结果
   * @throws BusinessException 扫描源停用（30002）、ZIP 未上传（30008）、规则无效（30003）、
   *                           规则类型不一致（30032）、MD 规则限制（30041）、版本号无效（30033）等
   */
  private ValidatedTask validate(TaskCreateDTO dto) {
    CodeRepository repository = repositoryService.getInternal(dto.getRepositoryId());
    if (!Boolean.TRUE.equals(repository.getEnabled())) throw new BusinessException(30002, "扫描源已停用");
    if ("UPLOAD".equals(repository.getSourceType()) && blank(repository.getStorageKey()))
      throw new BusinessException(30008, "ZIP扫描源尚未上传文件");
    List<ScanRule> rules = ruleMapper.findByIds(dto.getRuleIds());
    if (rules.size() != new HashSet<Long>(dto.getRuleIds()).size()) throw new BusinessException(30003, "存在无效或停用规则");
    String type = rules.get(0).getRuleType();
    for (ScanRule rule : rules) if (!type.equals(rule.getRuleType())) throw new BusinessException(30032, "同一任务只能选择同类型规则");
    if ("MD".equals(repository.getScanSourceType())) {
      if (!"AI".equals(type)) throw new BusinessException(30041, "MD扫描源只能选择AI规则");
      if (blank(repository.getVersionNo()) || !repository.getVersionNo().matches("\\d{6}"))
        throw new BusinessException(30033, "MD扫描源未配置有效版本");
      dto.setVersionNo(repository.getVersionNo());
    } else if (!blank(repository.getVersionNo()) && repository.getVersionNo().matches("\\d{6}")) {
      // CODE（Git/ZIP）扫描源：应用/版本取自扫描源配置并写入快照
      dto.setVersionNo(repository.getVersionNo());
    } else {
      dto.setVersionNo(null);
    }
    return new ValidatedTask(repository, rules, type);
  }

  /**
   * 采集 AI 任务所需的 prompt 模板快照（按任务类型与扫描源类型选取）。
   *
   * @param type           规则/任务类型（NORMAL / AI）
   * @param scanSourceType 扫描源类型（CODE / MD）
   * @return prompt 键值 Map，非 AI 任务返回空 Map
   */
  private Map<String, Object> promptSnapshot(String type, String scanSourceType) {
    Map<String, Object> prompts = new LinkedHashMap<String, Object>();
    if ("AI".equals(type)) {
      if ("MD".equals(scanSourceType)) prompts.put("MD_CHECK", modelConfigService.activePrompt("MD_CHECK"));
      else {
        prompts.put("AI_CHECK", modelConfigService.activePrompt("AI_CHECK"));
        prompts.put("AI_RESULT_UPDATE", modelConfigService.activePrompt("AI_RESULT_UPDATE"));
      }
    }
    return prompts;
  }

  /**
   * 持久化任务快照主记录。
   *
   * @param task       扫描任务
   * @param dto        创建/编辑 DTO
   * @param validated  校验结果
   * @param version    快照版本号
   * @param paths      扫描路径 JSON 源数据
   * @param types      文件类型 JSON 源数据
   * @param excludes   排除路径 JSON 源数据
   * @param prompts    prompt 快照 Map
   * @param hash       快照内容 SHA-256 摘要
   * @return 新快照 ID
   * @throws BusinessException 快照保存失败（30004）
   */
  private Long insertSnapshot(ScanTask task, TaskCreateDTO dto, ValidatedTask validated, int version,
      List<String> paths, List<String> types, List<String> excludes, Map<String, Object> prompts, String hash) {
    KeyHolder key = new GeneratedKeyHolder();
    try {
      String sourceSnapshot = json.writeValueAsString(validated.repository);
      String promptJson = json.writeValueAsString(prompts);
      String pathJson = json.writeValueAsString(paths), typeJson = json.writeValueAsString(types), excludeJson = json.writeValueAsString(excludes);
      jdbc.update(connection -> {
        PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO task_snapshot(task_id,snapshot_version,task_name_snapshot,description_snapshot,task_type,scan_source_type,repository_id,source_snapshot,application,version_no,scan_paths,file_types,exclude_paths,prompt_snapshot,snapshot_hash,created_by_user_id,created_by_user_name) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS);
        ps.setLong(1, task.getId()); ps.setInt(2, version); ps.setString(3, dto.getTaskName().trim()); ps.setString(4, dto.getDescription());
        ps.setString(5, validated.type); ps.setString(6, validated.repository.getScanSourceType()); ps.setLong(7, validated.repository.getId()); ps.setString(8, sourceSnapshot);
        ps.setString(9, validated.repository.getApplication()); ps.setString(10, dto.getVersionNo()); ps.setString(11, pathJson);
        ps.setString(12, typeJson); ps.setString(13, excludeJson); ps.setString(14, promptJson); ps.setString(15, hash);
        ps.setLong(16, AuditOperator.userId()); ps.setString(17, AuditOperator.userName()); return ps;
      }, key);
      return GeneratedIds.require(key);
    } catch (Exception e) { throw new BusinessException(30004, "任务快照保存失败：" + limit(e.getMessage(), 500)); }
  }

  /**
   * 插入扫描清单主记录（初始状态 BUILDING）。
   *
   * @param snapshotId 关联快照 ID
   * @param version    清单版本号
   * @return 新清单 ID
   */
  private Long insertManifest(Long snapshotId, int version) {
    KeyHolder key = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO task_scan_manifest(task_snapshot_id,manifest_version,status,start_time) VALUES(?,?,'BUILDING',CURRENT_TIMESTAMP)", Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, snapshotId); ps.setInt(2, version); return ps;
    }, key);
    return GeneratedIds.require(key);
  }

  /**
   * 确保任务不存在进行中的运行（QUEUED / RUNNING / STOP_REQUESTED / PAUSED）。
   *
   * @param taskId 任务 ID
   * @throws BusinessException 存在未结束运行（30034）
   */
  private void ensureNoOpenRun(Long taskId) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM scan_task_run WHERE task_id=? AND status IN ('QUEUED','RUNNING','STOP_REQUESTED','PAUSED')", new Object[] {taskId}, Integer.class);
    if (count != null && count > 0) throw new BusinessException(30034, "任务存在运行中或暂停待继续的运行");
  }

  /**
   * 在事务提交后发布 {@link TaskQueuedEvent}，避免消费者读取未提交数据。
   *
   * @param runId 待入队的运行 ID
   */
  private void publishAfterCommit(final Long runId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
        @Override public void afterCommit() { publisher.publishEvent(new TaskQueuedEvent(runId)); }
      });
    } else publisher.publishEvent(new TaskQueuedEvent(runId));
  }

  /**
   * 计算任务下一个快照版本号（当前最大版本 + 1）。
   *
   * @param taskId 任务 ID
   * @return 新快照版本号，无历史快照时返回 1
   */
  private int nextSnapshotVersion(Long taskId) {
    Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(snapshot_version),0)+1 FROM task_snapshot WHERE task_id=?", new Object[] {taskId}, Integer.class);
    return value == null ? 1 : value;
  }

  /**
   * 统计快照绑定的规则数量。
   *
   * @param snapshotId 快照 ID
   * @return 规则条数
   */
  private int countSnapshotRules(Long snapshotId) {
    Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM task_snapshot_rule WHERE task_snapshot_id=?", new Object[] {snapshotId}, Integer.class);
    return value == null ? 0 : value;
  }

  /**
   * 将 DTO 中的扫描范围序列化为 JSON 字符串，写入任务主表 scope_json。
   *
   * @param dto        创建/编辑 DTO
   * @param repository 扫描源实体
   * @return scope JSON 字符串
   * @throws BusinessException 序列化失败（30035）
   */
  private String scopeJson(TaskCreateDTO dto, CodeRepository repository) {
    Map<String, Object> scope = new LinkedHashMap<String, Object>();
    scope.put("scanPaths", normalizedPaths(dto.getScanPaths()));
    scope.put("fileTypes", normalizedTypes(dto.getFileTypes(), null));
    scope.put("excludePaths", normalizedExcludes(dto.getExcludePaths(), null));
    scope.put("versionNo", dto.getVersionNo());
    scope.put("scanSourceType", repository.getScanSourceType());
    try { return json.writeValueAsString(scope); } catch (Exception e) { throw new BusinessException(30035, "扫描范围格式错误"); }
  }

  /**
   * 归一化扫描相对路径列表，默认根路径为 {@code "."}。
   *
   * @param values 原始路径列表
   * @return 去重、归一化后的相对路径列表
   * @throws BusinessException 路径含 {@code ..} 或为绝对路径（30036）
   */
  private List<String> normalizedPaths(List<String> values) {
    if (values == null || values.isEmpty()) return Collections.singletonList(".");
    LinkedHashSet<String> out = new LinkedHashSet<String>();
    for (String value : values) {
      String normalized = blank(value) || "/".equals(value.trim()) ? "." : value.trim().replace('\\', '/');
      Path path = Paths.get(normalized).normalize();
      if (path.isAbsolute() || normalized.contains("..")) throw new BusinessException(30036, "扫描路径必须是安全相对路径");
      out.add(path.toString().replace('\\', '/'));
    }
    return new ArrayList<String>(out);
  }

  /**
   * 归一化文件扩展名列表；为空时使用 fallback 或全部 SUPPORTED_TYPES。
   *
   * @param values   原始扩展名列表
   * @param fallback 逗号/分号/空格分隔的备用扩展名字符串
   * @return 小写、去点前缀、且在 SUPPORTED_TYPES 内的扩展名列表
   * @throws BusinessException 无有效类型（30037）
   */
  private List<String> normalizedTypes(List<String> values, String fallback) {
    LinkedHashSet<String> out = new LinkedHashSet<String>();
    if (values != null) for (String value : values) if (!blank(value)) out.add(normalizeType(value));
    if (out.isEmpty() && !blank(fallback)) for (String value : fallback.split("[,;\\s]+")) if (!blank(value)) out.add(normalizeType(value));
    if (out.isEmpty()) out.addAll(SUPPORTED_TYPES);
    out.retainAll(SUPPORTED_TYPES);
    if (out.isEmpty()) throw new BusinessException(30037, "未选择系统支持的文件类型");
    return new ArrayList<String>(out);
  }

  /**
   * 归一化排除路径列表，默认包含 .git、node_modules、target。
   *
   * @param values   用户配置的排除路径
   * @param fallback 备用排除路径字符串
   * @return 去重后的排除路径片段列表
   * @throws BusinessException 排除路径不安全（30038）
   */
  private List<String> normalizedExcludes(List<String> values, String fallback) {
    LinkedHashSet<String> out = new LinkedHashSet<String>(Arrays.asList(".git", "node_modules", "target"));
    if (values != null) {
      for (String value : values) {
        if (!blank(value)) out.add(value.trim().replace('\\', '/'));
      }
    } else if (!blank(fallback)) {
      for (String value : fallback.split("[,;\\s]+")) {
        if (!blank(value)) out.add(value.trim().replace('\\', '/'));
      }
    }
    for (String value : out) if (value.contains("..") || Paths.get(value).isAbsolute()) throw new BusinessException(30038, "排除路径必须是安全相对路径");
    return new ArrayList<String>(out);
  }

  /**
   * 判断相对路径是否命中排除规则。
   *
   * @param relative 文件相对路径
   * @param excludes 排除路径片段列表
   * @return 命中任一排除规则时返回 {@code true}
   */
  private boolean excluded(String relative, List<String> excludes) {
    String path = "/" + relative + "/";
    for (String value : excludes) {
      String token = value.replace('\\', '/');
      if (path.contains("/" + token + "/") || relative.startsWith(token + "/")) return true;
    }
    return false;
  }

  /**
   * 将快照中 JSON 数组字段反序列化为字符串列表。
   *
   * @param value JSON 数组字符串
   * @return 字符串列表；空或 blank 时返回空列表
   * @throws BusinessException 解析失败（30039）
   */
  private List<String> readList(String value) {
    if (blank(value)) return new ArrayList<String>();
    try { return json.readValue(value, new TypeReference<List<String>>() {}); }
    catch (Exception e) { throw new BusinessException(30039, "任务快照列表字段无法解析"); }
  }

  /**
   * 计算文件的 SHA-256 十六进制摘要。
   *
   * @param file 文件路径
   * @return 小写 hex 摘要字符串
   * @throws Exception 读取或摘要算法异常
   */
  private String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192]; int read;
      while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
    }
    return hex(digest.digest());
  }

  /**
   * 将对象序列化为 JSON 后计算 SHA-256 摘要。
   *
   * @param value 待摘要对象
   * @return 小写 hex 摘要字符串
   * @throws BusinessException 序列化或摘要失败（30040）
   */
  private String sha256Json(Object value) {
    try { return sha256(json.writeValueAsString(value)); }
    catch (Exception e) { throw new BusinessException(30040, "快照摘要生成失败"); }
  }

  /**
   * 计算字符串的 SHA-256 十六进制摘要。
   *
   * @param value 输入字符串（UTF-8 编码）
   * @return 小写 hex 摘要字符串
   */
  private String sha256(String value) {
    try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  /**
   * 将字节数组转为小写十六进制字符串。
   *
   * @param bytes 原始字节
   * @return hex 字符串
   */
  private String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(); for (byte value : bytes) out.append(String.format("%02x", value)); return out.toString();
  }

  /**
   * 从文件路径提取并归一化扩展名。
   *
   * @param path 相对或绝对路径
   * @return 小写扩展名（无点）；无扩展名时返回空字符串
   */
  private String extension(String path) {
    int index = path.lastIndexOf('.'); return index < 0 ? "" : normalizeType(path.substring(index + 1));
  }

  /** 归一化文件扩展名：去空白、转小写、去前导点。 */
  private String normalizeType(String value) { return value.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""); }

  /** 判断字符串是否为 null 或仅含空白。 */
  private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

  /**
   * 修复历史数据：UTF-8 字节被按 ISO-8859-1 解码后的乱码（如 æˆ‘ → 我）。
   * 仅当修复后汉字明显增多时才替换，避免误伤正常文本。
   *
   * @param value 待修复的对象（String / Map / List 递归处理）
   * @return 修复后的对象；非容器类型原样返回
   */
  @SuppressWarnings("unchecked")
  private Object repairUtf8Mojibake(Object value) {
    if (value == null) return null;
    if (value instanceof String) return repairUtf8MojibakeString((String) value);
    if (value instanceof Map) {
      Map<String, Object> map = (Map<String, Object>) value;
      Map<String, Object> out = new LinkedHashMap<String, Object>();
      for (Map.Entry<String, Object> e : map.entrySet()) out.put(e.getKey(), repairUtf8Mojibake(e.getValue()));
      return out;
    }
    if (value instanceof List) {
      List<Object> list = (List<Object>) value;
      List<Object> out = new ArrayList<Object>(list.size());
      for (Object item : list) out.add(repairUtf8Mojibake(item));
      return out;
    }
    return value;
  }

  /**
   * 修复单条字符串的 UTF-8 乱码。
   *
   * @param value 原始字符串
   * @return 修复后的字符串；无需修复或修复失败时返回原值
   */
  private String repairUtf8MojibakeString(String value) {
    if (value == null || value.isEmpty()) return value;
    if (countHan(value) > 0) return value;
    if (value.indexOf('æ') < 0 && value.indexOf('å') < 0 && value.indexOf('ä') < 0) return value;
    try {
      String fixed = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
      if (countHan(fixed) > countHan(value)) return fixed;
    } catch (Exception ignored) {
      // keep original
    }
    return value;
  }

  /**
   * 统计字符串中 CJK 统一汉字（HAN）字符数量。
   *
   * @param value 输入字符串
   * @return 汉字个数
   */
  private int countHan(String value) {
    int n = 0;
    for (int i = 0; i < value.length(); i++) {
      if (Character.UnicodeScript.of(value.charAt(i)) == Character.UnicodeScript.HAN) n++;
    }
    return n;
  }

  /**
   * 归一化定时类型：非 ONCE 一律视为 NONE（不定时）。
   *
   * @param value 原始 scheduleType
   * @return {@code ONCE} 或 {@code NONE}
   */
  private String normalizeScheduleType(String value) {
    if ("ONCE".equalsIgnoreCase(value)) return "ONCE";
    return "NONE";
  }

  /**
   * 截断字符串至指定最大长度，用于持久化 error_message 等字段。
   *
   * @param value 原始字符串
   * @param size  最大长度
   * @return 截断后的字符串；null 时返回「未知错误」
   */
  private String limit(String value, int size) { if (value == null) return "未知错误"; return value.length() <= size ? value : value.substring(0, size); }

  /**
   * 将 ResultSet 当前行映射为 {@link TaskSnapshot}。
   *
   * @param rs JDBC ResultSet
   * @return 快照实体
   * @throws SQLException JDBC 读取异常
   */
  private TaskSnapshot snapshot(ResultSet rs) throws SQLException {
    TaskSnapshot v = new TaskSnapshot(); v.setId(rs.getLong("id")); v.setTaskId(rs.getLong("task_id"));
    v.setSnapshotVersion(rs.getInt("snapshot_version")); v.setTaskNameSnapshot(rs.getString("task_name_snapshot"));
    v.setDescriptionSnapshot(rs.getString("description_snapshot")); v.setTaskType(rs.getString("task_type"));
    v.setScanSourceType(rs.getString("scan_source_type"));
    v.setRepositoryId(rs.getLong("repository_id")); v.setSourceSnapshot(rs.getString("source_snapshot"));
    v.setApplication(rs.getString("application")); v.setVersionNo(rs.getString("version_no"));
    v.setScanPaths(rs.getString("scan_paths")); v.setFileTypes(rs.getString("file_types"));
    v.setExcludePaths(rs.getString("exclude_paths")); v.setPromptSnapshot(rs.getString("prompt_snapshot"));
    v.setSnapshotHash(rs.getString("snapshot_hash")); v.setServerRootPath(rs.getString("server_root_path"));
    v.setCreatedByUserId((Long) rs.getObject("created_by_user_id")); v.setCreatedByUserName(rs.getString("created_by_user_name"));
    v.setCreateTime(rs.getTimestamp("create_time")); return v;
  }

  /**
   * 将 ResultSet 当前行映射为 {@link TaskManifest}。
   *
   * @param rs JDBC ResultSet
   * @return 清单实体
   * @throws SQLException JDBC 读取异常
   */
  private TaskManifest manifest(ResultSet rs) throws SQLException {
    TaskManifest v = new TaskManifest(); v.setId(rs.getLong("id")); v.setTaskSnapshotId(rs.getLong("task_snapshot_id"));
    v.setManifestVersion(rs.getInt("manifest_version")); v.setStatus(rs.getString("status"));
    v.setFileCount(rs.getInt("file_count")); v.setExcludedCount(rs.getInt("excluded_count")); v.setTotalSize(rs.getLong("total_size"));
    v.setExcludeSummary(rs.getString("exclude_summary")); v.setManifestHash(rs.getString("manifest_hash"));
    v.setErrorMessage(rs.getString("error_message")); v.setStartTime(rs.getTimestamp("start_time"));
    v.setFinishTime(rs.getTimestamp("finish_time")); v.setCreateTime(rs.getTimestamp("create_time")); return v;
  }

  /**
   * 将 ResultSet 当前行映射为 {@link TaskManifestFile}。
   *
   * @param rs JDBC ResultSet
   * @return 清单文件实体
   * @throws SQLException JDBC 读取异常
   */
  private TaskManifestFile manifestFile(ResultSet rs) throws SQLException {
    TaskManifestFile v = new TaskManifestFile(); v.setId(rs.getLong("id")); v.setManifestId(rs.getLong("manifest_id"));
    v.setRelativePath(rs.getString("relative_path")); v.setFileType(rs.getString("file_type")); v.setFileSize(rs.getLong("file_size"));
    v.setLastModifiedTime(rs.getTimestamp("last_modified_time")); v.setContentHash(rs.getString("content_hash"));
    v.setApplicableRuleCount(rs.getInt("applicable_rule_count")); return v;
  }

  /**
   * 任务创建/编辑时的校验结果封装。
   */
  private static class ValidatedTask {

    /** 已校验的扫描源实体。 */
    private final CodeRepository repository;

    /** 已校验的规则列表（类型一致）。 */
    private final List<ScanRule> rules;

    /** 规则类型，即任务类型（NORMAL / AI）。 */
    private final String type;

    /**
     * 构造校验结果。
     *
     * @param repository 扫描源
     * @param rules      规则列表
     * @param type       任务/规则类型
     */
    private ValidatedTask(CodeRepository repository, List<ScanRule> rules, String type) { this.repository = repository; this.rules = rules; this.type = type; }
  }

  /**
   * 任务入队 Spring 事件，在事务提交后由调度器消费。
   */
  public static class TaskQueuedEvent {

    /** 待调度的运行 ID。 */
    private final Long runId;

    /**
     * 构造入队事件。
     *
     * @param runId 运行 ID
     */
    public TaskQueuedEvent(Long runId) { this.runId = runId; }

    /**
     * 获取运行 ID。
     *
     * @return 运行 ID
     */
    public Long getRunId() { return runId; }
  }
}
