package com.scan.center.service;

import com.scan.center.common.AuditOperator;
import com.scan.center.common.GeneratedIds;
import com.scan.center.dto.ModelCredentialSaveDTO;
import com.scan.center.dto.ModelPromptSaveDTO;
import com.scan.center.exception.BusinessException;
import com.scan.center.execution.AiModelHttpClient;
import com.scan.center.model.ModelCredential;
import com.scan.center.model.ModelPromptTemplate;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 大模型配置领域服务。
 *管理用户模型凭证（加解密、租约、连通测试）、内置提示词版本及 AI 运行参数（并发、定时窗口、Token 重试次数）。
 */
@Service
public class ModelConfigService {

  /** JDBC 访问，直接操作凭证、提示词与系统设置表 */
  private final JdbcTemplate jdbc;
  /** Token 加解密 */
  private final SecretCodec secretCodec;
  /** 大模型 HTTP 调用客户端，用于凭证连通测试 */
  private final AiModelHttpClient aiClient;

  /**
   * @param jdbc         Spring JdbcTemplate
   * @param secretCodec  凭据加解密
   * @param aiClient     AI 模型 HTTP 客户端
   */
  public ModelConfigService(JdbcTemplate jdbc, SecretCodec secretCodec, AiModelHttpClient aiClient) {
    this.jdbc = jdbc;
    this.secretCodec = secretCodec;
    this.aiClient = aiClient;
  }

  /**
   * 列出当前用户的模型凭证（不含密文，仅掩码展示）。
   *
   * @return 凭证列表，按创建时间倒序
   */
  public List<ModelCredential> credentials() {
    List<ModelCredential> values = jdbc.query(
        "SELECT * FROM user_model_credential WHERE user_id=? ORDER BY create_time DESC,id DESC",
        new Object[] {AuditOperator.userId()},
        (rs, row) -> credential(rs));
    for (ModelCredential value : values) value.setTokenCiphertext(null);
    return values;
  }

  /**
   * 新增模型凭证。
   *
   * @param dto 凭证名称、UCID、Token 及启用状态
   * @return 新凭证 ID
   * @throws BusinessException Token 为空（60003）
   */
  @Transactional(rollbackFor = Exception.class)
  public Long createCredential(ModelCredentialSaveDTO dto) {
    if (blank(dto.getToken())) throw new BusinessException(60003, "新增凭证必须填写Token");
    KeyHolder key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          PreparedStatement ps = connection.prepareStatement(
              "INSERT INTO user_model_credential(user_id,credential_name,ucid,token_ciphertext,token_masked,enabled,runtime_status) VALUES(?,?,?,?,?,?,'AVAILABLE')",
              Statement.RETURN_GENERATED_KEYS);
          ps.setLong(1, AuditOperator.userId());
          ps.setString(2, dto.getCredentialName().trim());
          ps.setString(3, dto.getUcid().trim());
          ps.setString(4, secretCodec.encrypt(dto.getToken()));
          ps.setString(5, mask(dto.getToken()));
          ps.setBoolean(6, dto.getEnabled() == null || dto.getEnabled());
          return ps;
        },
        key);
    return GeneratedIds.require(key);
  }

  /**
   * 更新模型凭证；Token 留空则保留原密文。
   *
   * @param id  凭证 ID
   * @param dto 更新参数
   * @throws BusinessException 凭证不存在或无权修改（60004）
   */
  @Transactional(rollbackFor = Exception.class)
  public void updateCredential(Long id, ModelCredentialSaveDTO dto) {
    ModelCredential old = credentialInternal(id);
    String ciphertext = blank(dto.getToken()) ? old.getTokenCiphertext() : secretCodec.encrypt(dto.getToken());
    String masked = blank(dto.getToken()) ? old.getTokenMasked() : mask(dto.getToken());
    int changed = jdbc.update(
        "UPDATE user_model_credential SET credential_name=?,ucid=?,token_ciphertext=?,token_masked=?,enabled=?,runtime_status='AVAILABLE',lease_id=NULL,leased_run_id=NULL,lease_expire_time=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",
        dto.getCredentialName().trim(), dto.getUcid().trim(), ciphertext, masked,
        dto.getEnabled() == null ? old.getEnabled() : dto.getEnabled(), id, AuditOperator.userId());
    if (changed != 1) throw new BusinessException(60004, "模型凭证不存在或无权修改");
  }

  /**
   * 删除模型凭证；BUSY 状态（执行中租约）不可删。
   *
   * @param id 凭证 ID
   * @throws BusinessException 不存在、无权或正在使用（60005）
   */
  public void deleteCredential(Long id) {
    if (jdbc.update("DELETE FROM user_model_credential WHERE id=? AND user_id=? AND runtime_status<>'BUSY'", id, AuditOperator.userId()) != 1)
      throw new BusinessException(60005, "凭证不存在、无权删除或正在使用");
  }

  /**
   * 启用或停用模型凭证。
   *
   * @param id      凭证 ID
   * @param enabled 目标启用状态
   * @throws BusinessException 凭证不存在或无权访问（60004）
   */
  public void credentialStatus(Long id, boolean enabled) {
    credentialInternal(id);
    jdbc.update(
        "UPDATE user_model_credential SET enabled=?,runtime_status=CASE WHEN ? THEN 'AVAILABLE' ELSE runtime_status END,update_time=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",
        enabled, enabled, id, AuditOperator.userId());
  }

  /**
   * 调用大模型做一次连通测试并更新 last_test_time。
   *
   * @param id 凭证 ID
   * @return 模型原始响应文本
   * @throws BusinessException 凭证不存在或无权访问（60004）
   */
  public String testCredential(Long id) {
    ModelCredential value = credentialInternal(id);
    String result = aiClient.invoke(value, "你是连接测试助手，只返回JSON。", "请返回 {\"ok\":true}");
    jdbc.update("UPDATE user_model_credential SET last_test_time=CURRENT_TIMESTAMP,runtime_status='AVAILABLE',update_time=CURRENT_TIMESTAMP WHERE id=?", id);
    return result;
  }

  /**
   * 查询内置提示词模板列表。
   *
   * @param type 提示词类型，空则查全部
   * @return 模板列表，按类型与版本号倒序
   */
  public List<ModelPromptTemplate> prompts(String type) {
    String sql = "SELECT * FROM model_prompt_template" + (blank(type) ? "" : " WHERE prompt_type=?") + " ORDER BY prompt_type,version_no DESC";
    return blank(type)
        ? jdbc.query(sql, (rs, row) -> prompt(rs))
        : jdbc.query(sql, new Object[] {type}, (rs, row) -> prompt(rs));
  }

  /**
   * 获取指定类型当前 ACTIVE 的最新版本提示词。
   *
   * @param type 提示词类型（AI_CHECK/AI_RESULT_UPDATE/MD_CHECK）
   * @return 启用的提示词模板
   * @throws BusinessException 无 ACTIVE 版本（60006）
   */
  public ModelPromptTemplate activePrompt(String type) {
    List<ModelPromptTemplate> values = jdbc.query(
        "SELECT * FROM model_prompt_template WHERE prompt_type=? AND status='ACTIVE' ORDER BY version_no DESC LIMIT 1",
        new Object[] {type}, (rs, row) -> prompt(rs));
    if (values.isEmpty()) throw new BusinessException(60006, "未配置启用的内置提示词：" + type);
    return values.get(0);
  }

  /**
   * 新建提示词草稿（版本号在同类型内自增）。
   *
   * @param dto 提示词类型、内容与 JSON Schema
   * @return 新模板 ID
   * @throws BusinessException 提示词类型不合法（60009）
   */
  @Transactional(rollbackFor = Exception.class)
  public Long createPrompt(ModelPromptSaveDTO dto) {
    validatePromptType(dto.getPromptType());
    Integer version = jdbc.queryForObject(
        "SELECT COALESCE(MAX(version_no),0)+1 FROM model_prompt_template WHERE prompt_type=?",
        new Object[] {dto.getPromptType()}, Integer.class);
    KeyHolder key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          PreparedStatement ps = connection.prepareStatement(
              "INSERT INTO model_prompt_template(prompt_type,version_no,prompt_content,json_schema,status,operator_user_id,operator_user_name) VALUES(?,?,?,?, 'DRAFT',?,?)",
              Statement.RETURN_GENERATED_KEYS);
          ps.setString(1, dto.getPromptType());
          ps.setInt(2, version);
          ps.setString(3, dto.getPromptContent());
          ps.setString(4, dto.getJsonSchema());
          ps.setLong(5, AuditOperator.userId());
          ps.setString(6, AuditOperator.userName());
          return ps;
        }, key);
    return GeneratedIds.require(key);
  }

  /**
   * 更新 DRAFT 状态提示词内容。
   *
   * @param id  模板 ID
   * @param dto 更新参数
   * @throws BusinessException 非草稿或不存在（60007）或类型不合法（60009）
   */
  public void updatePrompt(Long id, ModelPromptSaveDTO dto) {
    validatePromptType(dto.getPromptType());
    if (jdbc.update(
            "UPDATE model_prompt_template SET prompt_content=?,json_schema=?,operator_user_id=?,operator_user_name=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND prompt_type=? AND status='DRAFT'",
            dto.getPromptContent(), dto.getJsonSchema(), AuditOperator.userId(), AuditOperator.userName(), id, dto.getPromptType()) != 1)
      throw new BusinessException(60007, "仅允许编辑存在的提示词草稿");
  }

  /**
   * 将指定草稿设为 ACTIVE，同类型原 ACTIVE 降为 INACTIVE。
   *
   * @param id 模板 ID
   * @throws BusinessException 提示词不存在（60008）
   */
  @Transactional(rollbackFor = Exception.class)
  public void activatePrompt(Long id) {
    List<ModelPromptTemplate> values = jdbc.query("SELECT * FROM model_prompt_template WHERE id=?", new Object[] {id}, (rs, row) -> prompt(rs));
    if (values.isEmpty()) throw new BusinessException(60008, "提示词不存在");
    ModelPromptTemplate value = values.get(0);
    jdbc.update("UPDATE model_prompt_template SET status='INACTIVE',update_time=CURRENT_TIMESTAMP WHERE prompt_type=? AND status='ACTIVE'", value.getPromptType());
    jdbc.update("UPDATE model_prompt_template SET status='ACTIVE',operator_user_id=?,operator_user_name=?,update_time=CURRENT_TIMESTAMP WHERE id=?", AuditOperator.userId(), AuditOperator.userName(), id);
  }

  /**
   * 为扫描运行抢占一条可用凭证（乐观锁租约，默认 5 分钟）。
   *
   * @param userId 任务所属用户 ID
   * @param runId  扫描运行 ID，写入 leased_run_id
   * @return 已 BUSY 且含 leaseId 的凭证；无可用凭证时返回 null
   */
  @Transactional(rollbackFor = Exception.class)
  public ModelCredential acquireCredential(Long userId, Long runId) {
    List<Long> ids = jdbc.query(
        "SELECT id FROM user_model_credential WHERE user_id=? AND enabled=TRUE AND (runtime_status='AVAILABLE' OR lease_expire_time<CURRENT_TIMESTAMP OR (runtime_status='COOLDOWN' AND cooldown_until<CURRENT_TIMESTAMP)) ORDER BY CASE WHEN last_used_time IS NULL THEN 0 ELSE 1 END,last_used_time,id LIMIT 20",
        new Object[] {userId}, (rs, row) -> rs.getLong(1));
    for (Long id : ids) {
      String lease = UUID.randomUUID().toString();
      Timestamp leaseExpire = new Timestamp(System.currentTimeMillis() + 5 * 60 * 1000L);
      int changed = jdbc.update(
          "UPDATE user_model_credential SET runtime_status='BUSY',lease_id=?,leased_run_id=?,lease_expire_time=?,last_used_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=? AND enabled=TRUE AND (runtime_status='AVAILABLE' OR lease_expire_time<CURRENT_TIMESTAMP OR (runtime_status='COOLDOWN' AND cooldown_until<CURRENT_TIMESTAMP))",
          lease, runId, leaseExpire, id);
      if (changed == 1) {
        ModelCredential value = credentialById(id);
        value.setLeaseId(lease);
        return value;
      }
    }
    return null;
  }

  /**
   * 释放凭证租约，恢复为 AVAILABLE。
   *
   * @param credential 含 id 与 leaseId 的凭证，null 时忽略
   */
  public void releaseCredential(ModelCredential credential) {
    if (credential == null) return;
    jdbc.update(
        "UPDATE user_model_credential SET runtime_status='AVAILABLE',lease_id=NULL,leased_run_id=NULL,lease_expire_time=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND lease_id=?",
        credential.getId(), credential.getLeaseId());
  }

  /**
   * 统计用户已启用且非 INVALID 的凭证数量。
   *
   * @param userId 用户 ID
   * @return 可用凭证数
   */
  public int availableCredentialCount(Long userId) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM user_model_credential WHERE user_id=? AND enabled=TRUE AND runtime_status<>'INVALID'",
        new Object[] {userId}, Integer.class);
    return count == null ? 0 : count;
  }

  /**
   * 读取 AI Token 调用失败重试次数（系统设置，默认 3，范围 0~10）。
   *
   * @return 重试次数
   */
  public int tokenRetryCount() {
    List<String> values = jdbc.query("SELECT setting_value FROM system_setting WHERE setting_key='AI_TOKEN_RETRY_COUNT'",
        (rs, row) -> rs.getString(1));
    if (values.isEmpty()) return 3;
    try { return Math.max(0, Math.min(10, Integer.parseInt(values.get(0)))); }
    catch (Exception ignored) { return 3; }
  }

  /**
   * 管理员更新 Token 重试次数。
   *
   * @param retryCount 0~10
   * @throws BusinessException 非管理员（60011）或数值越界（60010）
   */
  @Transactional(rollbackFor = Exception.class)
  public void updateTokenRetryCount(int retryCount) {
    ensureAdmin();
    if (retryCount < 0 || retryCount > 10) throw new BusinessException(60010, "Token重试次数必须在0到10之间");
    upsertSetting("AI_TOKEN_RETRY_COUNT", String.valueOf(retryCount), "大模型Token调用失败后的重试次数");
  }

  /**
   * 读取服务器可同时运行的 AI 扫描任务并发上限（默认 1，范围 1~50）。
   *
   * @return 并发上限
   */
  public int aiScanTaskConcurrency() {
    List<String> values = jdbc.query("SELECT setting_value FROM system_setting WHERE setting_key='AI_SCAN_TASK_CONCURRENCY'",
        (rs, row) -> rs.getString(1));
    if (values.isEmpty()) return 1;
    try { return Math.max(1, Math.min(50, Integer.parseInt(values.get(0)))); }
    catch (Exception ignored) { return 1; }
  }

  /**
   * 管理员更新 AI 扫描任务并发上限。
   *
   * @param concurrency 1~50
   * @throws BusinessException 非管理员（60011）或数值越界（60012）
   */
  @Transactional(rollbackFor = Exception.class)
  public void updateAiScanTaskConcurrency(int concurrency) {
    ensureAdmin();
    if (concurrency < 1 || concurrency > 50) throw new BusinessException(60012, "AI扫描任务并发数必须在1到50之间");
    upsertSetting("AI_SCAN_TASK_CONCURRENCY", String.valueOf(concurrency), "服务器可同时运行的AI类型扫描任务数量");
  }

  /**
   * AI 定时发起窗口开始时刻，格式 HH:mm，默认 20:00。
   *
   * @return 窗口开始时间
   */
  public String aiScheduleWindowStart() {
    return settingOrDefault("AI_SCHEDULE_WINDOW_START", "20:00");
  }

  /**
   * AI 定时发起窗口结束时刻，格式 HH:mm，默认 08:00（可跨天）。
   *
   * @return 窗口结束时间
   */
  public String aiScheduleWindowEnd() {
    return settingOrDefault("AI_SCHEDULE_WINDOW_END", "08:00");
  }

  /**
   * 返回 AI 定时窗口起止时刻。
   *
   * @return 含 start、end 键的 Map
   */
  public Map<String, Object> aiScheduleWindow() {
    Map<String, Object> value = new LinkedHashMap<String, Object>();
    value.put("start", aiScheduleWindowStart());
    value.put("end", aiScheduleWindowEnd());
    return value;
  }

  /**
   * 管理员更新 AI 定时发起窗口。
   *
   * @param start 开始时刻 HH:mm
   * @param end   结束时刻 HH:mm
   * @throws BusinessException 非管理员（60011）或时间格式非法（60013）
   */
  @Transactional(rollbackFor = Exception.class)
  public void updateAiScheduleWindow(String start, String end) {
    ensureAdmin();
    String s = normalizeHm(start);
    String e = normalizeHm(end);
    if (s == null || e == null) throw new BusinessException(60013, "时间格式必须为 HH:mm，例如 20:00");
    upsertSetting("AI_SCHEDULE_WINDOW_START", s, "AI任务允许定时发起的开始时刻（含跨天）");
    upsertSetting("AI_SCHEDULE_WINDOW_END", e, "AI任务允许定时发起的结束时刻（含跨天）");
  }

  /**
   * 判断给定时刻是否落在 AI 定时窗口内。
   *start小于end：同日区间；start大于end：跨天（如 20:00~次日08:00）；相等视为全天允许。
   *
   * @param time 待判断时刻
   * @return 在窗口内为 true；time 为 null 时 false
   */
  public boolean isInAiScheduleWindow(java.util.Date time) {
    if (time == null) return false;
    int minutes = minutesOfDay(time);
    int start = minutesOfHm(aiScheduleWindowStart());
    int end = minutesOfHm(aiScheduleWindowEnd());
    if (start == end) return true;
    if (start < end) return minutes >= start && minutes < end;
    return minutes >= start || minutes < end;
  }

  /**
   * AI 单次定时任务创建前校验 scheduleTime 落在允许窗口内。
   *
   * @param taskType     任务类型，仅 AI 生效
   * @param scheduleType 调度类型，仅 ONCE 生效
   * @param scheduleTime 计划发起时间
   * @throws BusinessException 未选时间（30043）或不在窗口内（30044）
   */
  public void ensureAiScheduleWindow(String taskType, String scheduleType, java.util.Date scheduleTime) {
    if (!"AI".equals(taskType) || !"ONCE".equals(scheduleType)) return;
    if (scheduleTime == null) throw new BusinessException(30043, "请选择AI任务定时发起时间");
    if (!isInAiScheduleWindow(scheduleTime)) {
      throw new BusinessException(30044,
          "AI任务定时发起时间必须落在允许窗口内（" + aiScheduleWindowStart() + " ~ "
              + aiScheduleWindowEnd() + "，可跨天）。普通任务不受此限制。请调整时间或联系管理员修改运行参数。");
    }
  }

  /** 读取系统设置项，无值或空时返回默认值。 */
  private String settingOrDefault(String key, String defaultValue) {
    List<String> values = jdbc.query("SELECT setting_value FROM system_setting WHERE setting_key=?",
        new Object[] {key}, (rs, row) -> rs.getString(1));
    if (values.isEmpty() || values.get(0) == null || values.get(0).trim().isEmpty()) return defaultValue;
    return values.get(0).trim();
  }

  /** 校验并规范化 HH:mm 字符串，非法返回 null。 */
  private String normalizeHm(String value) {
    if (value == null) return null;
    String v = value.trim();
    if (!v.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) return null;
    return v;
  }

  /** 将 HH:mm 转为当日分钟数（0~1439）。 */
  private int minutesOfHm(String hm) {
    String[] parts = hm.split(":");
    return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
  }

  /** 提取 Date 在本地时区的当日分钟数。 */
  private int minutesOfDay(java.util.Date time) {
    java.util.Calendar cal = java.util.Calendar.getInstance();
    cal.setTime(time);
    return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
  }

  /**
   * 当前占用 AI 并发槽位的任务数（QUEUED/RUNNING/STOP_REQUESTED；暂停任务不占槽）。
   *
   * @return 活跃 AI 任务数
   */
  public int activeAiScanTaskCount() {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM scan_task WHERE deleted=FALSE AND task_type='AI' AND status IN ('QUEUED','RUNNING','STOP_REQUESTED')",
        Integer.class);
    return count == null ? 0 : count;
  }

  /**
   * 返回 AI 扫描并发配额与当前占用情况。
   *
   * @return 含 limit、activeCount、available 的 Map
   */
  public Map<String, Object> aiScanConcurrencyStatus() {
    Map<String, Object> value = new LinkedHashMap<String, Object>();
    int limit = aiScanTaskConcurrency();
    int active = activeAiScanTaskCount();
    value.put("limit", limit);
    value.put("activeCount", active);
    value.put("available", Math.max(0, limit - active));
    return value;
  }

  /** 更新或插入系统设置项。 */
  private void upsertSetting(String key, String value, String description) {
    int changed = jdbc.update(
        "UPDATE system_setting SET setting_value=?,operator_user_id=?,operator_user_name=?,update_time=CURRENT_TIMESTAMP WHERE setting_key=?",
        value, AuditOperator.userId(), AuditOperator.userName(), key);
    if (changed == 0) {
      jdbc.update(
          "INSERT INTO system_setting(setting_key,setting_value,description,operator_user_id,operator_user_name) VALUES(?,?,?,?,?)",
          key, value, description, AuditOperator.userId(), AuditOperator.userName());
    }
  }

  /** 断言当前用户为已启用管理员。 */
  private void ensureAdmin() {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM system_user WHERE id=? AND role_code='ADMIN' AND enabled=TRUE AND deleted=FALSE",
        new Object[] {AuditOperator.userId()}, Integer.class);
    if (count == null || count == 0) throw new BusinessException(60011, "仅管理员可以修改运行参数");
  }

  /**
   * 按 ID 获取当前用户拥有的凭证（含密文）。
   *
   * @param id 凭证 ID
   * @return 凭证实体
   * @throws BusinessException 不存在或无权（60004）
   */
  private ModelCredential credentialInternal(Long id) {
    List<ModelCredential> values = jdbc.query(
        "SELECT * FROM user_model_credential WHERE id=? AND user_id=?",
        new Object[] {id, AuditOperator.userId()}, (rs, row) -> credential(rs));
    if (values.isEmpty()) throw new BusinessException(60004, "模型凭证不存在或无权访问");
    return values.get(0);
  }

  /** 按 ID 查询凭证（不限 user_id，供租约抢占后加载完整记录）。 */
  private ModelCredential credentialById(Long id) {
    return jdbc.queryForObject("SELECT * FROM user_model_credential WHERE id=?", new Object[] {id}, (rs, row) -> credential(rs));
  }

  /** 从 ResultSet 映射 ModelCredential 实体。 */
  private ModelCredential credential(ResultSet rs) throws SQLException {
    ModelCredential v = new ModelCredential();
    v.setId(rs.getLong("id")); v.setUserId(rs.getLong("user_id"));
    v.setCredentialName(rs.getString("credential_name")); v.setUcid(rs.getString("ucid"));
    v.setTokenCiphertext(rs.getString("token_ciphertext")); v.setTokenMasked(rs.getString("token_masked"));
    v.setEnabled(rs.getBoolean("enabled")); v.setRuntimeStatus(rs.getString("runtime_status"));
    v.setLeaseId(rs.getString("lease_id")); v.setLeasedRunId((Long) rs.getObject("leased_run_id"));
    v.setLeaseExpireTime(rs.getTimestamp("lease_expire_time")); v.setCooldownUntil(rs.getTimestamp("cooldown_until"));
    v.setLastUsedTime(rs.getTimestamp("last_used_time")); v.setLastTestTime(rs.getTimestamp("last_test_time"));
    v.setCreateTime(rs.getTimestamp("create_time")); v.setUpdateTime(rs.getTimestamp("update_time"));
    return v;
  }

  /** 从 ResultSet 映射 ModelPromptTemplate 实体。 */
  private ModelPromptTemplate prompt(ResultSet rs) throws SQLException {
    ModelPromptTemplate v = new ModelPromptTemplate();
    v.setId(rs.getLong("id")); v.setPromptType(rs.getString("prompt_type"));
    v.setVersionNo(rs.getInt("version_no")); v.setPromptContent(rs.getString("prompt_content"));
    v.setJsonSchema(rs.getString("json_schema")); v.setStatus(rs.getString("status"));
    v.setOperatorUserId((Long) rs.getObject("operator_user_id")); v.setOperatorUserName(rs.getString("operator_user_name"));
    v.setCreateTime(rs.getTimestamp("create_time")); v.setUpdateTime(rs.getTimestamp("update_time"));
    return v;
  }

  /**
   * 校验提示词类型枚举。
   *
   * @param type 提示词类型
   * @throws BusinessException 类型不在允许列表（60009）
   */
  private void validatePromptType(String type) {
    if (!Arrays.asList("AI_CHECK", "AI_RESULT_UPDATE", "MD_CHECK").contains(type))
      throw new BusinessException(60009, "内置提示词类型不合法");
  }

  /** 生成 Token 掩码：长度≤8 为 ****，否则首尾各 4 位可见。 */
  private String mask(String token) {
    if (token.length() <= 8) return "****";
    return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
  }

  /** 判断字符串是否为 null 或空白。 */
  private boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
