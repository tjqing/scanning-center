package com.scan.center.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan.center.common.*;
import com.scan.center.dto.TaskCreateDTO;
import com.scan.center.exception.BusinessException;
import com.scan.center.execution.SourceWorkspaceService;
import com.scan.center.mapper.*;
import com.scan.center.model.*;
import java.io.InputStream;
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

@Service
public class TaskService {
  private static final Set<String> SUPPORTED_TYPES = new LinkedHashSet<String>(Arrays.asList(
      "java", "js", "ts", "vue", "xml", "yml", "yaml", "json", "md", "txt", "sql",
      "properties", "html", "css", "py", "sh", "docx", "pdf"));

  private final TaskMapper mapper;
  private final RuleMapper ruleMapper;
  private final RepositoryService repositoryService;
  private final ModelConfigService modelConfigService;
  private final SourceWorkspaceService workspaceService;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ApplicationEventPublisher publisher;

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

  public PageResult<ScanTask> page(String keyword, String status, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    return new PageResult<ScanTask>(
        mapper.page(keyword, status, (p - 1) * s, s), p, s, mapper.count(keyword, status));
  }

  public ScanTask get(Long id) {
    ScanTask value = mapper.findById(id);
    if (value == null) throw new BusinessException(30001, "扫描任务不存在");
    return value;
  }

  public List<TaskSnapshot> snapshots(Long taskId) {
    get(taskId);
    return jdbc.query(
        "SELECT * FROM task_snapshot WHERE task_id=? ORDER BY snapshot_version DESC",
        new Object[] {taskId}, (rs, row) -> snapshot(rs));
  }

  public TaskSnapshot snapshot(Long taskId, Long snapshotId) {
    get(taskId);
    List<TaskSnapshot> values = jdbc.query(
        "SELECT * FROM task_snapshot WHERE id=? AND task_id=?",
        new Object[] {snapshotId, taskId}, (rs, row) -> snapshot(rs));
    if (values.isEmpty()) throw new BusinessException(30022, "任务快照不存在");
    return values.get(0);
  }

  public TaskManifest currentManifest(Long taskId) {
    ScanTask task = get(taskId);
    if (task.getCurrentSnapshotId() == null) throw new BusinessException(30023, "任务尚未生成快照");
    List<TaskManifest> values = jdbc.query(
        "SELECT * FROM task_scan_manifest WHERE task_snapshot_id=? ORDER BY manifest_version DESC LIMIT 1",
        new Object[] {task.getCurrentSnapshotId()}, (rs, row) -> manifest(rs));
    if (values.isEmpty()) throw new BusinessException(30024, "扫描清单不存在");
    return values.get(0);
  }

  public PageResult<TaskManifestFile> manifestFiles(Long manifestId, String keyword, String fileType, int page, int size) {
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
    return new PageResult<TaskManifestFile>(files, p, s, total == null ? 0 : total);
  }

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
    task.setManifestStatus("BUILDING");
    task.setOwnerUserId(AuditOperator.USER_ID);
    task.setOwnerUserName(AuditOperator.USER_NAME);
    task.setOperatorUserId(AuditOperator.USER_ID);
    task.setOperatorUserName(AuditOperator.USER_NAME);
    mapper.insert(task);
    createSnapshotAndManifest(task, dto, validated);
    if (Boolean.TRUE.equals(dto.getExecuteImmediately())) execute(task.getId());
    return task.getId();
  }

  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, TaskCreateDTO dto) {
    ScanTask task = get(id);
    ensureNoOpenRun(id);
    ValidatedTask validated = validate(dto);
    jdbc.update(
        "UPDATE scan_task SET task_name=?,description=?,repository_id=?,task_type=?,scope_json=?,manifest_status='BUILDING',operator_user_id=?,operator_user_name=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=FALSE",
        dto.getTaskName().trim(), dto.getDescription(), dto.getRepositoryId(), validated.type,
        scopeJson(dto, validated.repository), AuditOperator.USER_ID, AuditOperator.USER_NAME, id);
    task.setTaskName(dto.getTaskName().trim()); task.setDescription(dto.getDescription());
    task.setRepositoryId(dto.getRepositoryId()); task.setTaskType(validated.type);
    createSnapshotAndManifest(task, dto, validated);
  }

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

  @Transactional(rollbackFor = Exception.class)
  public Long regenerateManifest(Long taskId) {
    ScanTask task = get(taskId);
    ensureNoOpenRun(taskId);
    TaskSnapshot snapshot = snapshot(taskId, task.getCurrentSnapshotId());
    return generateManifest(task, snapshot, readList(snapshot.getScanPaths()), readList(snapshot.getFileTypes()), readList(snapshot.getExcludePaths()),
        countSnapshotRules(snapshot.getId()));
  }

  @Transactional(rollbackFor = Exception.class)
  public void execute(Long id) {
    ScanTask task = get(id);
    ensureNoOpenRun(id);
    TaskSnapshot snapshot = snapshot(id, task.getCurrentSnapshotId());
    TaskManifest manifest = currentManifest(id);
    if (!"READY".equals(manifest.getStatus())) throw new BusinessException(30025, "扫描清单尚未就绪");
    verifyManifest(snapshot, manifest);
    if ("AI".equals(snapshot.getTaskType())
        && modelConfigService.availableCredentialCount(task.getOwnerUserId()) == 0)
      throw new BusinessException(30026, "AI任务至少需要一条启用且有效的UCID/Token");
    Long runId = insertRun(task, snapshot, manifest);
    int units = createExecutionUnits(runId, manifest.getId(), snapshot.getId(), snapshot.getTaskType(), snapshot.getScanSourceType());
    jdbc.update("UPDATE scan_task_run SET total_units=? WHERE id=?", units, runId);
    jdbc.update(
        "UPDATE scan_task SET current_run_id=?,status='QUEUED',cancel_requested=FALSE,total_files=?,completed_files=0,success_files=0,failed_files=0,issue_count=0,error_message=NULL,start_time=NULL,end_time=NULL,update_time=CURRENT_TIMESTAMP WHERE id=?",
        runId, manifest.getFileCount(), id);
    publishAfterCommit(runId);
  }

  @Transactional(rollbackFor = Exception.class)
  public void stop(Long taskId) {
    ScanTask task = get(taskId);
    if (task.getCurrentRunId() == null) throw new BusinessException(30006, "当前任务没有可停止的运行");
    int queued = jdbc.update("UPDATE scan_task_run SET status='PAUSED',stop_requested=TRUE,update_time=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'", task.getCurrentRunId());
    int running = queued == 0 ? jdbc.update("UPDATE scan_task_run SET status='STOP_REQUESTED',stop_requested=TRUE,update_time=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'", task.getCurrentRunId()) : 0;
    if (queued + running == 0) throw new BusinessException(30006, "当前任务状态不允许停止");
    jdbc.update("UPDATE scan_task SET status=?,cancel_requested=TRUE,update_time=CURRENT_TIMESTAMP WHERE id=?", queued == 1 ? "PAUSED" : "STOP_REQUESTED", taskId);
  }

  @Transactional(rollbackFor = Exception.class)
  public void resume(Long taskId) {
    ScanTask task = get(taskId);
    if (task.getCurrentRunId() == null) throw new BusinessException(30027, "当前任务没有可继续的运行");
    if ("AI".equals(task.getTaskType())
        && modelConfigService.availableCredentialCount(task.getOwnerUserId()) == 0)
      throw new BusinessException(30026, "继续任务前请启用至少一条有效UCID/Token");
    if (jdbc.update("UPDATE scan_task_run SET status='QUEUED',stop_requested=FALSE,resume_count=resume_count+1,error_message=NULL,queue_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=? AND status='PAUSED'", task.getCurrentRunId()) != 1)
      throw new BusinessException(30027, "只有暂停运行可以继续");
    jdbc.update("UPDATE scan_task SET status='QUEUED',cancel_requested=FALSE,error_message=NULL,update_time=CURRENT_TIMESTAMP WHERE id=?", taskId);
    publishAfterCommit(task.getCurrentRunId());
  }

  @Transactional(rollbackFor = Exception.class)
  public void abort(Long taskId) {
    ScanTask task = get(taskId);
    if (task.getCurrentRunId() == null || jdbc.update("UPDATE scan_task_run SET status='ABORTED',end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=? AND status='PAUSED'", task.getCurrentRunId()) != 1)
      throw new BusinessException(30028, "只有暂停运行可以放弃");
    jdbc.update("UPDATE scan_task SET status='ABORTED',end_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?", taskId);
  }

  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    get(id); ensureNoOpenRun(id);
    jdbc.update("UPDATE scan_task SET deleted=TRUE,status='DELETED',update_time=CURRENT_TIMESTAMP WHERE id=?", id);
  }

  private void createSnapshotAndManifest(ScanTask task, TaskCreateDTO dto, ValidatedTask validated) {
    int version = nextSnapshotVersion(task.getId());
    Map<String, Object> prompts = promptSnapshot(validated.type, validated.repository.getScanSourceType());
    List<String> paths = normalizedPaths(dto.getScanPaths());
    List<String> types = normalizedTypes(dto.getFileTypes(), validated.repository.getFileTypes());
    List<String> excludes = normalizedExcludes(dto.getExcludePaths(), validated.repository.getExcludePatterns());
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
      jdbc.update("UPDATE scan_task SET current_snapshot_id=?,manifest_status='READY',status='READY',manifest_file_count=(SELECT file_count FROM task_scan_manifest WHERE id=?),update_time=CURRENT_TIMESTAMP WHERE id=?", snapshotId, manifestId, task.getId());
    } catch (BusinessException e) {
      jdbc.update("UPDATE scan_task SET manifest_status='FAILED',status='DRAFT',error_message=? WHERE id=?", limit(e.getMessage(), 1800), task.getId());
      throw e;
    } catch (Exception e) {
      throw new BusinessException(30004, "任务快照生成失败：" + limit(e.getMessage(), 1000));
    }
  }

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

  private Long insertRun(ScanTask task, TaskSnapshot snapshot, TaskManifest manifest) {
    KeyHolder key = new GeneratedKeyHolder();
    String no = "RUN" + System.currentTimeMillis() + String.format("%03d", new Random().nextInt(1000));
    jdbc.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO scan_task_run(run_no,task_id,task_snapshot_id,manifest_id,status,stop_requested,started_by_user_id,started_by_user_name,queue_time) VALUES(?,?,?,?, 'QUEUED',FALSE,?,?,CURRENT_TIMESTAMP)", Statement.RETURN_GENERATED_KEYS);
      ps.setString(1, no); ps.setLong(2, task.getId()); ps.setLong(3, snapshot.getId()); ps.setLong(4, manifest.getId());
      ps.setLong(5, AuditOperator.USER_ID); ps.setString(6, AuditOperator.USER_NAME); return ps;
    }, key);
    return key.getKey().longValue();
  }

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
      if (blank(dto.getVersionNo()) || !dto.getVersionNo().matches("\\d{6}"))
        throw new BusinessException(30033, "MD扫描源任务必须填写YYYYMM格式版本");
    }
    return new ValidatedTask(repository, rules, type);
  }

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
        ps.setLong(16, AuditOperator.USER_ID); ps.setString(17, AuditOperator.USER_NAME); return ps;
      }, key);
      return key.getKey().longValue();
    } catch (Exception e) { throw new BusinessException(30004, "任务快照保存失败：" + limit(e.getMessage(), 500)); }
  }

  private Long insertManifest(Long snapshotId, int version) {
    KeyHolder key = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO task_scan_manifest(task_snapshot_id,manifest_version,status,start_time) VALUES(?,?,'BUILDING',CURRENT_TIMESTAMP)", Statement.RETURN_GENERATED_KEYS);
      ps.setLong(1, snapshotId); ps.setInt(2, version); return ps;
    }, key);
    return key.getKey().longValue();
  }

  private void ensureNoOpenRun(Long taskId) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM scan_task_run WHERE task_id=? AND status IN ('QUEUED','RUNNING','STOP_REQUESTED','PAUSED')", new Object[] {taskId}, Integer.class);
    if (count != null && count > 0) throw new BusinessException(30034, "任务存在运行中或暂停待继续的运行");
  }

  private void publishAfterCommit(final Long runId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
        @Override public void afterCommit() { publisher.publishEvent(new TaskQueuedEvent(runId)); }
      });
    } else publisher.publishEvent(new TaskQueuedEvent(runId));
  }

  private int nextSnapshotVersion(Long taskId) {
    Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(snapshot_version),0)+1 FROM task_snapshot WHERE task_id=?", new Object[] {taskId}, Integer.class);
    return value == null ? 1 : value;
  }

  private int countSnapshotRules(Long snapshotId) {
    Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM task_snapshot_rule WHERE task_snapshot_id=?", new Object[] {snapshotId}, Integer.class);
    return value == null ? 0 : value;
  }

  private String scopeJson(TaskCreateDTO dto, CodeRepository repository) {
    Map<String, Object> scope = new LinkedHashMap<String, Object>();
    scope.put("scanPaths", normalizedPaths(dto.getScanPaths()));
    scope.put("fileTypes", normalizedTypes(dto.getFileTypes(), repository.getFileTypes()));
    scope.put("excludePaths", normalizedExcludes(dto.getExcludePaths(), repository.getExcludePatterns()));
    scope.put("versionNo", dto.getVersionNo());
    scope.put("scanSourceType", repository.getScanSourceType());
    try { return json.writeValueAsString(scope); } catch (Exception e) { throw new BusinessException(30035, "扫描范围格式错误"); }
  }

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

  private List<String> normalizedTypes(List<String> values, String fallback) {
    LinkedHashSet<String> out = new LinkedHashSet<String>();
    if (values != null) for (String value : values) if (!blank(value)) out.add(normalizeType(value));
    if (out.isEmpty() && !blank(fallback)) for (String value : fallback.split("[,;\\s]+")) if (!blank(value)) out.add(normalizeType(value));
    if (out.isEmpty()) out.addAll(SUPPORTED_TYPES);
    out.retainAll(SUPPORTED_TYPES);
    if (out.isEmpty()) throw new BusinessException(30037, "未选择系统支持的文件类型");
    return new ArrayList<String>(out);
  }

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

  private boolean excluded(String relative, List<String> excludes) {
    String path = "/" + relative + "/";
    for (String value : excludes) {
      String token = value.replace('\\', '/');
      if (path.contains("/" + token + "/") || relative.startsWith(token + "/")) return true;
    }
    return false;
  }

  private List<String> readList(String value) {
    if (blank(value)) return new ArrayList<String>();
    try { return json.readValue(value, new TypeReference<List<String>>() {}); }
    catch (Exception e) { throw new BusinessException(30039, "任务快照列表字段无法解析"); }
  }

  private String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192]; int read;
      while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
    }
    return hex(digest.digest());
  }

  private String sha256Json(Object value) {
    try { return sha256(json.writeValueAsString(value)); }
    catch (Exception e) { throw new BusinessException(30040, "快照摘要生成失败"); }
  }

  private String sha256(String value) {
    try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  private String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(); for (byte value : bytes) out.append(String.format("%02x", value)); return out.toString();
  }

  private String extension(String path) {
    int index = path.lastIndexOf('.'); return index < 0 ? "" : normalizeType(path.substring(index + 1));
  }

  private String normalizeType(String value) { return value.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""); }
  private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
  private String limit(String value, int size) { if (value == null) return "未知错误"; return value.length() <= size ? value : value.substring(0, size); }

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

  private TaskManifest manifest(ResultSet rs) throws SQLException {
    TaskManifest v = new TaskManifest(); v.setId(rs.getLong("id")); v.setTaskSnapshotId(rs.getLong("task_snapshot_id"));
    v.setManifestVersion(rs.getInt("manifest_version")); v.setStatus(rs.getString("status"));
    v.setFileCount(rs.getInt("file_count")); v.setExcludedCount(rs.getInt("excluded_count")); v.setTotalSize(rs.getLong("total_size"));
    v.setExcludeSummary(rs.getString("exclude_summary")); v.setManifestHash(rs.getString("manifest_hash"));
    v.setErrorMessage(rs.getString("error_message")); v.setStartTime(rs.getTimestamp("start_time"));
    v.setFinishTime(rs.getTimestamp("finish_time")); v.setCreateTime(rs.getTimestamp("create_time")); return v;
  }

  private TaskManifestFile manifestFile(ResultSet rs) throws SQLException {
    TaskManifestFile v = new TaskManifestFile(); v.setId(rs.getLong("id")); v.setManifestId(rs.getLong("manifest_id"));
    v.setRelativePath(rs.getString("relative_path")); v.setFileType(rs.getString("file_type")); v.setFileSize(rs.getLong("file_size"));
    v.setLastModifiedTime(rs.getTimestamp("last_modified_time")); v.setContentHash(rs.getString("content_hash"));
    v.setApplicableRuleCount(rs.getInt("applicable_rule_count")); return v;
  }

  private static class ValidatedTask {
    private final CodeRepository repository; private final List<ScanRule> rules; private final String type;
    private ValidatedTask(CodeRepository repository, List<ScanRule> rules, String type) { this.repository = repository; this.rules = rules; this.type = type; }
  }

  public static class TaskQueuedEvent {
    private final Long runId;
    public TaskQueuedEvent(Long runId) { this.runId = runId; }
    public Long getRunId() { return runId; }
  }
}
