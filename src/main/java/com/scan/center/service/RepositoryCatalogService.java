package com.scan.center.service;

import com.scan.center.common.ApplicationOptions;
import com.scan.center.common.AuditOperator;
import com.scan.center.common.PageResult;
import com.scan.center.dto.RepositoryCatalogSaveDTO;
import com.scan.center.exception.BusinessException;
import com.scan.center.mapper.RepositoryCatalogMapper;
import com.scan.center.mapper.UserMapper;
import com.scan.center.model.RepositoryCatalog;
import com.scan.center.model.SystemUser;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 代码库目录（Git 仓库元数据与凭据）领域服务。
 *管理员维护代码库注册信息；普通用户通过用户-代码库关联访问；凭据加密存储，拉代码场景可解密。
 */
@Service
public class RepositoryCatalogService {

  /** SSH 私钥认证方式 */
  public static final String AUTH_SSH = "SSH_KEY";
  /** HTTPS Token 认证方式 */
  public static final String AUTH_HTTPS = "HTTPS_TOKEN";

  /** 代码库目录数据访问层 */
  private final RepositoryCatalogMapper mapper;
  /** 用户数据访问层，用于权限与关联关系 */
  private final UserMapper userMapper;
  /** 凭据加解密服务 */
  private final SecretCodec secretCodec;

  /**
   * @param mapper       代码库目录 Mapper
   * @param userMapper   用户 Mapper
   * @param secretCodec  凭据加解密
   */
  public RepositoryCatalogService(RepositoryCatalogMapper mapper, UserMapper userMapper, SecretCodec secretCodec) {
    this.mapper = mapper;
    this.userMapper = userMapper;
    this.secretCodec = secretCodec;
  }

  /**
   * 管理员分页查询代码库目录。
   *
   * @param keyword     关键词
   * @param application 应用筛选，可为空
   * @param enabled     启用状态
   * @param page        页码
   * @param size        每页条数，限制在 1~100
   * @return 代码库分页结果
   * @throws BusinessException 非管理员或应用筛选不合法
   */
  public PageResult<RepositoryCatalog> page(String keyword, String application, Boolean enabled, int page, int size) {
    ensureAdmin(); validateApplicationFilter(application);
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    return new PageResult<RepositoryCatalog>(mapper.page(keyword, application, enabled, (p - 1) * s, s), p, s,
        mapper.count(keyword, application, enabled));
  }

  /**
   * 管理员查询指定应用下已启用的代码库列表（供下拉选择）。
   *
   * @param application 应用编码
   * @return 已启用代码库列表
   * @throws BusinessException 非管理员或应用不合法（60004）
   */
  public List<RepositoryCatalog> available(String application) {
    ensureAdmin();
    if (!ApplicationOptions.contains(application)) throw new BusinessException(60004, "应用选项不合法");
    return mapper.findEnabledByApplication(application.trim());
  }

  /**
   * 当前用户可见的已启用代码库；管理员可见全部（按 ALL 查询）。
   *
   * @return 代码库列表
   * @throws BusinessException 当前用户无效（60009）
   */
  public List<RepositoryCatalog> mine() {
    SystemUser user = currentUser();
    return mapper.findEnabledByApplication("ADMIN".equals(user.getRoleCode()) ? "ALL" : user.getApplication());
  }

  /**
   * 校验当前用户有权访问的代码库（须已启用且存在用户关联，管理员除外）。
   *
   * @param id 代码库 ID
   * @return 代码库实体（不含解密凭据）
   * @throws BusinessException 不存在/已停用（60001）或无权访问（60007）
   */
  public RepositoryCatalog accessible(Long id) {
    RepositoryCatalog value = mapper.findById(id);
    if (value == null || !Boolean.TRUE.equals(value.getEnabled())) throw new BusinessException(60001, "代码库不存在或已停用");
    SystemUser user = currentUser();
    if (!"ADMIN".equals(user.getRoleCode()) && userMapper.countRepositoryRelation(user.getId(), id) == 0)
      throw new BusinessException(60007, "无权访问该代码库");
    return value;
  }

  /**
   * 管理员按 ID 获取代码库详情。
   *
   * @param id 代码库 ID
   * @return 代码库实体
   * @throws BusinessException 非管理员或代码库不存在（60001）
   */
  public RepositoryCatalog get(Long id) {
    ensureAdmin(); RepositoryCatalog value = mapper.findById(id);
    if (value == null) throw new BusinessException(60001, "代码库不存在"); return value;
  }

  /**
   * 拉取代码专用：返回含加密密文的完整记录，勿直接暴露给前端。
   *
   * @param id 代码库 ID，null 时返回 null
   * @return 代码库实体或 null
   */
  public RepositoryCatalog loadForClone(Long id) {
    if (id == null) return null;
    return mapper.findById(id);
  }

  /**
   * 解密代码库 Git 凭据（拉取时用，与「保存时加密入库」是另一条链路）。
   *
   * @param catalog 代码库实体，可为 null
   * @return 明文凭据，无密文时返回空字符串
   */
  public String decryptSecret(RepositoryCatalog catalog) {
    if (catalog == null || blank(catalog.getEncryptedSecret())) return "";
    return secretCodec.decrypt(catalog.getEncryptedSecret());
  }

  /**
   * 管理员新建代码库并建立用户关联。
   *
   * @param dto 保存参数
   * @return 新代码库 ID
   * @throws BusinessException 权限或校验失败
   */
  @Transactional(rollbackFor = Exception.class)
  public Long create(RepositoryCatalogSaveDTO dto) {
    ensureAdmin(); validate(dto, null, true);
    RepositoryCatalog value = value(dto, null);
    mapper.insert(value);
    if (Boolean.TRUE.equals(value.getEnabled())) linkUsers(value);
    return value.getId();
  }

  /**
   * 管理员更新代码库；重建用户关联关系。
   *
   * @param id  代码库 ID
   * @param dto 保存参数
   * @throws BusinessException 代码库不存在或校验失败
   */
  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, RepositoryCatalogSaveDTO dto) {
    RepositoryCatalog existing = get(id);
    validate(dto, id, false);
    RepositoryCatalog value = value(dto, existing);
    value.setId(id);
    mapper.deleteUserRelations(id);
    mapper.update(value);
    if (Boolean.TRUE.equals(value.getEnabled())) linkUsers(value);
  }

  /**
   * 启用或停用代码库；状态变更时同步用户关联。
   *
   * @param id      代码库 ID
   * @param enabled 目标启用状态
   * @throws BusinessException 代码库不存在（60001）
   */
  @Transactional(rollbackFor = Exception.class)
  public void status(Long id, boolean enabled) {
    RepositoryCatalog value = get(id);
    mapper.deleteUserRelations(id);
    mapper.updateStatus(id, enabled);
    if (enabled) linkUsers(value);
  }

  /**
   * 逻辑删除代码库；已关联用户时不允许删除。
   *
   * @param id 代码库 ID
   * @throws BusinessException 代码库不存在（60001）或已关联用户（60003）
   */
  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    get(id);
    if (mapper.countUserReference(id) > 0) throw new BusinessException(60003, "代码库已关联用户，只能停用");
    mapper.logicalDelete(id);
  }

  /**
   * 将 DTO 转为持久化实体（含认证方式与 Git 凭据落库）。
   *
   * @param dto      保存参数
   * @param existing 更新时的原记录，新建时为 null
   * @return 待持久化的代码库实体
   * @throws BusinessException 无可用 SSH 私钥时可抛出（60012）
   */
  private RepositoryCatalog value(RepositoryCatalogSaveDTO dto, RepositoryCatalog existing) {
    RepositoryCatalog value = new RepositoryCatalog();
    BeanUtils.copyProperties(dto, value);
    value.setRepositoryName(dto.getRepositoryName().trim());
    value.setRepositoryUrl(dto.getRepositoryUrl().trim());
    value.setApplication(dto.getApplication().trim());
    value.setVersionNo(dto.getVersionNo().trim());
    String auth = blank(dto.getAuthType())
        ? (existing != null && !blank(existing.getAuthType()) ? existing.getAuthType() : AUTH_SSH)
        : normalizeAuth(dto.getAuthType());
    value.setAuthType(auth);
    value.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
    value.setOperatorUserId(AuditOperator.userId());
    value.setOperatorUserName(AuditOperator.userName());
    applyGitCredential(value, dto, existing, auth);
    return value;
  }

  /**
   * 为代码库写入 Git 凭据到 encrypted_secret。
   * 前端已不传私钥；当前顺序：dto 明文加密 → 编辑保留原密文 → 拷贝库内已有 SSH 密文。
   *
   * TODO 后期改本方法：从统一凭据源查询 → SecretCodec.encrypt → 写入 value；拉取解密仍用 decryptSecret。
   *
   * @param value    待写入实体
   * @param dto      保存入参（secret 可空）
   * @param existing 原记录，新建为 null
   * @param auth     已规范化的认证方式
   */
  private void applyGitCredential(
      RepositoryCatalog value, RepositoryCatalogSaveDTO dto, RepositoryCatalog existing, String auth) {
    if (!blank(dto.getSecret())) {
      String secret = dto.getSecret().trim();
      if (AUTH_SSH.equals(auth)) secret = sanitizeSshPrivateKey(secret);
      value.setEncryptedSecret(secretCodec.encrypt(secret));
      value.setGitUsername(blank(dto.getGitUsername()) ? null : dto.getGitUsername().trim());
      return;
    }
    if (existing != null && !blank(existing.getEncryptedSecret())) {
      value.setEncryptedSecret(existing.getEncryptedSecret());
      value.setGitUsername(existing.getGitUsername());
      if (!blank(existing.getAuthType())) value.setAuthType(existing.getAuthType());
      return;
    }
    RepositoryCatalog shared = mapper.findSharedSshCredential();
    if (shared == null || blank(shared.getEncryptedSecret())) {
      throw new BusinessException(60012, "数据库中尚未配置可用的 SSH 私钥，请先在库内写入一条带私钥的代码库记录");
    }
    value.setAuthType(AUTH_SSH);
    value.setEncryptedSecret(shared.getEncryptedSecret());
    value.setGitUsername(shared.getGitUsername());
  }

  /**
   * 清洗 SSH 私钥文本：统一换行、去除 PEM 正文内多余空白，避免终端复制导致认证失败。
   *
   * @param raw 原始私钥字符串
   * @return 规范化后的 PEM 文本（末尾保留换行）
   */
  private String sanitizeSshPrivateKey(String raw) {
    String key = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
    if (key.contains("\\n") && !key.contains("\n")) key = key.replace("\\n", "\n");
    StringBuilder cleaned = new StringBuilder();
    boolean inBody = false;
    for (String line : key.split("\n", -1)) {
      String t = line.replaceAll("[ \\t]+$", "");
      if (t.contains("BEGIN") && t.contains("PRIVATE KEY")) {
        cleaned.append(t).append('\n');
        inBody = true;
        continue;
      }
      if (t.contains("END") && t.contains("PRIVATE KEY")) {
        cleaned.append(t).append('\n');
        inBody = false;
        continue;
      }
      if (inBody) cleaned.append(t.replace(" ", "").replace("\t", "")).append('\n');
      else if (!t.isEmpty()) cleaned.append(t).append('\n');
    }
    return cleaned.toString().trim() + "\n";
  }

  /**
   * 按代码库所属应用建立用户-代码库关联关系。
   *
   * @param value 已持久化的代码库（须含 id 与 application）
   */
  private void linkUsers(RepositoryCatalog value) {
    mapper.insertUserRelations(value.getId(), value.getApplication(), AuditOperator.userId(), AuditOperator.userName());
  }

  /**
   * 保存前校验：应用、版本、仓库名唯一性、URL 格式及凭据可用性。
   *
   * @param dto      保存参数
   * @param id       更新时的 ID，新建为 null
   * @param creating 是否为新建
   * @throws BusinessException 任一校验项不合法
   */
  private void validate(RepositoryCatalogSaveDTO dto, Long id, boolean creating) {
    if (!ApplicationOptions.contains(dto.getApplication()))
      throw new BusinessException(60004, "请选择有效应用");
    if (blank(dto.getVersionNo()) || !dto.getVersionNo().trim().matches("\\d{6}"))
      throw new BusinessException(60015, "请选择有效版本（YYYYMM）");
    if (!dto.getRepositoryName().trim().matches("[^/\\s]+/[^/\\s]+"))
      throw new BusinessException(60005, "代码库名称必须使用xxx/xxx格式");
    if (mapper.countName(dto.getRepositoryName().trim(), id) > 0)
      throw new BusinessException(60002, "代码库名称已存在");
    if (!blank(dto.getAuthType())) {
      String auth = normalizeAuth(dto.getAuthType());
      if (!AUTH_SSH.equals(auth) && !AUTH_HTTPS.equals(auth))
        throw new BusinessException(60010, "认证方式必须是 SSH_KEY 或 HTTPS_TOKEN");
    }
    String url = dto.getRepositoryUrl().trim();
    if (!(url.startsWith("git@") || url.startsWith("ssh://") || url.startsWith("http://") || url.startsWith("https://")))
      throw new BusinessException(60011, "请使用 git@host:group/repo.git，或 https:// 地址（拉取时按 SSH 自动转换）");
    // 编辑且未传私钥：原记录无凭据且库内也无可复用时提前失败（实际写入见 applyGitCredential）
    if (!creating && blank(dto.getSecret()) && id != null) {
      RepositoryCatalog old = mapper.findById(id);
      if (old != null && blank(old.getEncryptedSecret()) && mapper.findSharedSshCredential() == null)
        throw new BusinessException(60012, "该代码库无凭据，且数据库中也没有可复用的 SSH 私钥");
    }
  }

  /** 认证方式枚举值规范化为大写 trim 字符串。 */
  private String normalizeAuth(String authType) {
    return authType == null ? "" : authType.trim().toUpperCase(Locale.ROOT);
  }

  /**
   * 校验分页筛选中的应用参数（非空时须在 ApplicationOptions 内）。
   *
   * @param application 应用筛选值
   * @throws BusinessException 应用不合法（60004）
   */
  private void validateApplicationFilter(String application) {
    if (application != null && !application.trim().isEmpty() && !ApplicationOptions.contains(application))
      throw new BusinessException(60004, "应用选项不合法");
  }

  /** 断言当前登录用户为管理员。 */
  private void ensureAdmin() {
    SystemUser user = currentUser();
    if (user == null || !"ADMIN".equals(user.getRoleCode())) throw new BusinessException(60006, "仅管理员可以维护代码库");
  }

  /**
   * 获取当前审计上下文中的有效登录用户。
   *
   * @return 系统用户
   * @throws BusinessException 用户不存在或已停用（60009）
   */
  private SystemUser currentUser() {
    SystemUser user = userMapper.findById(AuditOperator.userId());
    if (user == null || !Boolean.TRUE.equals(user.getEnabled())) throw new BusinessException(60009, "当前用户不存在或已停用");
    return user;
  }

  /** 判断字符串是否为 null 或空白。 */
  private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
