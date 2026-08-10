package com.scan.center.service;

import com.scan.center.common.AuditOperator;
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

@Service
public class ModelConfigService {
  private final JdbcTemplate jdbc;
  private final SecretCodec secretCodec;
  private final AiModelHttpClient aiClient;

  public ModelConfigService(JdbcTemplate jdbc, SecretCodec secretCodec, AiModelHttpClient aiClient) {
    this.jdbc = jdbc;
    this.secretCodec = secretCodec;
    this.aiClient = aiClient;
  }

  public List<ModelCredential> credentials() {
    List<ModelCredential> values = jdbc.query(
        "SELECT * FROM user_model_credential WHERE user_id=? ORDER BY create_time DESC,id DESC",
        new Object[] {AuditOperator.USER_ID},
        (rs, row) -> credential(rs));
    for (ModelCredential value : values) value.setTokenCiphertext(null);
    return values;
  }

  @Transactional(rollbackFor = Exception.class)
  public Long createCredential(ModelCredentialSaveDTO dto) {
    if (blank(dto.getToken())) throw new BusinessException(60003, "新增凭证必须填写Token");
    KeyHolder key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          PreparedStatement ps = connection.prepareStatement(
              "INSERT INTO user_model_credential(user_id,credential_name,ucid,token_ciphertext,token_masked,enabled,runtime_status) VALUES(?,?,?,?,?,?,'AVAILABLE')",
              Statement.RETURN_GENERATED_KEYS);
          ps.setLong(1, AuditOperator.USER_ID);
          ps.setString(2, dto.getCredentialName().trim());
          ps.setString(3, dto.getUcid().trim());
          ps.setString(4, secretCodec.encrypt(dto.getToken()));
          ps.setString(5, mask(dto.getToken()));
          ps.setBoolean(6, dto.getEnabled() == null || dto.getEnabled());
          return ps;
        },
        key);
    return key.getKey().longValue();
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateCredential(Long id, ModelCredentialSaveDTO dto) {
    ModelCredential old = credentialInternal(id);
    String ciphertext = blank(dto.getToken()) ? old.getTokenCiphertext() : secretCodec.encrypt(dto.getToken());
    String masked = blank(dto.getToken()) ? old.getTokenMasked() : mask(dto.getToken());
    int changed = jdbc.update(
        "UPDATE user_model_credential SET credential_name=?,ucid=?,token_ciphertext=?,token_masked=?,enabled=?,runtime_status='AVAILABLE',lease_id=NULL,leased_run_id=NULL,lease_expire_time=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",
        dto.getCredentialName().trim(), dto.getUcid().trim(), ciphertext, masked,
        dto.getEnabled() == null ? old.getEnabled() : dto.getEnabled(), id, AuditOperator.USER_ID);
    if (changed != 1) throw new BusinessException(60004, "模型凭证不存在或无权修改");
  }

  public void deleteCredential(Long id) {
    if (jdbc.update("DELETE FROM user_model_credential WHERE id=? AND user_id=? AND runtime_status<>'BUSY'", id, AuditOperator.USER_ID) != 1)
      throw new BusinessException(60005, "凭证不存在、无权删除或正在使用");
  }

  public void credentialStatus(Long id, boolean enabled) {
    credentialInternal(id);
    jdbc.update(
        "UPDATE user_model_credential SET enabled=?,runtime_status=CASE WHEN ? THEN 'AVAILABLE' ELSE runtime_status END,update_time=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",
        enabled, enabled, id, AuditOperator.USER_ID);
  }

  public String testCredential(Long id) {
    ModelCredential value = credentialInternal(id);
    String result = aiClient.invoke(value, "你是连接测试助手，只返回JSON。", "请返回 {\"ok\":true}");
    jdbc.update("UPDATE user_model_credential SET last_test_time=CURRENT_TIMESTAMP,runtime_status='AVAILABLE',update_time=CURRENT_TIMESTAMP WHERE id=?", id);
    return result;
  }

  public List<ModelPromptTemplate> prompts(String type) {
    String sql = "SELECT * FROM model_prompt_template" + (blank(type) ? "" : " WHERE prompt_type=?") + " ORDER BY prompt_type,version_no DESC";
    return blank(type)
        ? jdbc.query(sql, (rs, row) -> prompt(rs))
        : jdbc.query(sql, new Object[] {type}, (rs, row) -> prompt(rs));
  }

  public ModelPromptTemplate activePrompt(String type) {
    List<ModelPromptTemplate> values = jdbc.query(
        "SELECT * FROM model_prompt_template WHERE prompt_type=? AND status='ACTIVE' ORDER BY version_no DESC LIMIT 1",
        new Object[] {type}, (rs, row) -> prompt(rs));
    if (values.isEmpty()) throw new BusinessException(60006, "未配置启用的内置提示词：" + type);
    return values.get(0);
  }

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
          ps.setLong(5, AuditOperator.USER_ID);
          ps.setString(6, AuditOperator.USER_NAME);
          return ps;
        }, key);
    return key.getKey().longValue();
  }

  public void updatePrompt(Long id, ModelPromptSaveDTO dto) {
    validatePromptType(dto.getPromptType());
    if (jdbc.update(
            "UPDATE model_prompt_template SET prompt_content=?,json_schema=?,operator_user_id=?,operator_user_name=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND prompt_type=? AND status='DRAFT'",
            dto.getPromptContent(), dto.getJsonSchema(), AuditOperator.USER_ID, AuditOperator.USER_NAME, id, dto.getPromptType()) != 1)
      throw new BusinessException(60007, "仅允许编辑存在的提示词草稿");
  }

  @Transactional(rollbackFor = Exception.class)
  public void activatePrompt(Long id) {
    List<ModelPromptTemplate> values = jdbc.query("SELECT * FROM model_prompt_template WHERE id=?", new Object[] {id}, (rs, row) -> prompt(rs));
    if (values.isEmpty()) throw new BusinessException(60008, "提示词不存在");
    ModelPromptTemplate value = values.get(0);
    jdbc.update("UPDATE model_prompt_template SET status='INACTIVE',update_time=CURRENT_TIMESTAMP WHERE prompt_type=? AND status='ACTIVE'", value.getPromptType());
    jdbc.update("UPDATE model_prompt_template SET status='ACTIVE',operator_user_id=?,operator_user_name=?,update_time=CURRENT_TIMESTAMP WHERE id=?", AuditOperator.USER_ID, AuditOperator.USER_NAME, id);
  }

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

  public void releaseCredential(ModelCredential credential) {
    if (credential == null) return;
    jdbc.update(
        "UPDATE user_model_credential SET runtime_status='AVAILABLE',lease_id=NULL,leased_run_id=NULL,lease_expire_time=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND lease_id=?",
        credential.getId(), credential.getLeaseId());
  }

  public int availableCredentialCount(Long userId) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM user_model_credential WHERE user_id=? AND enabled=TRUE AND runtime_status<>'INVALID'",
        new Object[] {userId}, Integer.class);
    return count == null ? 0 : count;
  }

  public int tokenRetryCount() {
    List<String> values = jdbc.query("SELECT setting_value FROM system_setting WHERE setting_key='AI_TOKEN_RETRY_COUNT'",
        (rs, row) -> rs.getString(1));
    if (values.isEmpty()) return 3;
    try { return Math.max(0, Math.min(10, Integer.parseInt(values.get(0)))); }
    catch (Exception ignored) { return 3; }
  }

  @Transactional(rollbackFor = Exception.class)
  public void updateTokenRetryCount(int retryCount) {
    ensureAdmin();
    if (retryCount < 0 || retryCount > 10) throw new BusinessException(60010, "Token重试次数必须在0到10之间");
    int changed = jdbc.update("UPDATE system_setting SET setting_value=?,operator_user_id=?,operator_user_name=?,update_time=CURRENT_TIMESTAMP WHERE setting_key='AI_TOKEN_RETRY_COUNT'",
        String.valueOf(retryCount), AuditOperator.USER_ID, AuditOperator.USER_NAME);
    if (changed == 0) jdbc.update("INSERT INTO system_setting(setting_key,setting_value,description,operator_user_id,operator_user_name) VALUES('AI_TOKEN_RETRY_COUNT',?,'大模型Token调用失败后的重试次数',?,?)",
        String.valueOf(retryCount), AuditOperator.USER_ID, AuditOperator.USER_NAME);
  }

  private void ensureAdmin() {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM system_user WHERE id=? AND role_code='ADMIN' AND enabled=TRUE AND deleted=FALSE",
        new Object[] {AuditOperator.USER_ID}, Integer.class);
    if (count == null || count == 0) throw new BusinessException(60011, "仅管理员可以配置Token重试次数");
  }

  private ModelCredential credentialInternal(Long id) {
    List<ModelCredential> values = jdbc.query(
        "SELECT * FROM user_model_credential WHERE id=? AND user_id=?",
        new Object[] {id, AuditOperator.USER_ID}, (rs, row) -> credential(rs));
    if (values.isEmpty()) throw new BusinessException(60004, "模型凭证不存在或无权访问");
    return values.get(0);
  }

  private ModelCredential credentialById(Long id) {
    return jdbc.queryForObject("SELECT * FROM user_model_credential WHERE id=?", new Object[] {id}, (rs, row) -> credential(rs));
  }

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

  private ModelPromptTemplate prompt(ResultSet rs) throws SQLException {
    ModelPromptTemplate v = new ModelPromptTemplate();
    v.setId(rs.getLong("id")); v.setPromptType(rs.getString("prompt_type"));
    v.setVersionNo(rs.getInt("version_no")); v.setPromptContent(rs.getString("prompt_content"));
    v.setJsonSchema(rs.getString("json_schema")); v.setStatus(rs.getString("status"));
    v.setOperatorUserId((Long) rs.getObject("operator_user_id")); v.setOperatorUserName(rs.getString("operator_user_name"));
    v.setCreateTime(rs.getTimestamp("create_time")); v.setUpdateTime(rs.getTimestamp("update_time"));
    return v;
  }

  private void validatePromptType(String type) {
    if (!Arrays.asList("AI_CHECK", "AI_RESULT_UPDATE", "MD_CHECK").contains(type))
      throw new BusinessException(60009, "内置提示词类型不合法");
  }

  private String mask(String token) {
    if (token.length() <= 8) return "****";
    return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
  }

  private boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
