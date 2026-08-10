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
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepositoryCatalogService {
  private final RepositoryCatalogMapper mapper;
  private final UserMapper userMapper;
  private final String gitUsername;
  private final String gitToken;

  public RepositoryCatalogService(RepositoryCatalogMapper mapper, UserMapper userMapper,
      @Value("${scan-center.git.username:}") String gitUsername,
      @Value("${scan-center.git.token:}") String gitToken) {
    this.mapper = mapper; this.userMapper = userMapper;
    this.gitUsername = gitUsername == null ? "" : gitUsername;
    this.gitToken = gitToken == null ? "" : gitToken;
  }

  public PageResult<RepositoryCatalog> page(String keyword, String application, Boolean enabled, int page, int size) {
    ensureAdmin(); validateApplicationFilter(application);
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    return new PageResult<RepositoryCatalog>(mapper.page(keyword, application, enabled, (p - 1) * s, s), p, s,
        mapper.count(keyword, application, enabled));
  }

  public List<RepositoryCatalog> available(String application) {
    ensureAdmin();
    if (!ApplicationOptions.contains(application)) throw new BusinessException(60004, "应用选项不合法");
    return mapper.findEnabledByApplication(application.trim());
  }

  public List<RepositoryCatalog> mine() {
    SystemUser user = currentUser();
    return mapper.findEnabledByApplication("ADMIN".equals(user.getRoleCode()) ? "ALL" : user.getApplication());
  }

  public RepositoryCatalog accessible(Long id) {
    RepositoryCatalog value = mapper.findById(id);
    if (value == null || !Boolean.TRUE.equals(value.getEnabled())) throw new BusinessException(60001, "代码库不存在或已停用");
    SystemUser user = currentUser();
    if (!"ADMIN".equals(user.getRoleCode()) && userMapper.countRepositoryRelation(user.getId(), id) == 0)
      throw new BusinessException(60007, "无权访问该代码库");
    return value;
  }

  public List<String> branches(Long id) {
    RepositoryCatalog value = accessible(id);
    try {
      org.eclipse.jgit.api.LsRemoteCommand command = Git.lsRemoteRepository().setRemote(value.getRepositoryUrl()).setHeads(true);
      if (!gitToken.isEmpty()) command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(gitUsername.isEmpty() ? "token" : gitUsername, gitToken));
      return command.call().stream().map(Ref::getName).filter(name -> name.startsWith("refs/heads/"))
          .map(name -> name.substring("refs/heads/".length())).sorted().collect(Collectors.toList());
    } catch (Exception e) {
      throw new BusinessException(60008, "远程分支查询失败：" + limit(e.getMessage(), 300));
    }
  }

  public RepositoryCatalog get(Long id) {
    ensureAdmin(); RepositoryCatalog value = mapper.findById(id);
    if (value == null) throw new BusinessException(60001, "代码库不存在"); return value;
  }

  @Transactional(rollbackFor = Exception.class)
  public Long create(RepositoryCatalogSaveDTO dto) {
    ensureAdmin(); validate(dto, null); RepositoryCatalog value = value(dto); mapper.insert(value);
    if (Boolean.TRUE.equals(value.getEnabled())) linkUsers(value); return value.getId();
  }

  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, RepositoryCatalogSaveDTO dto) {
    get(id); validate(dto, id); RepositoryCatalog value = value(dto); value.setId(id);
    mapper.deleteUserRelations(id); mapper.update(value); if (Boolean.TRUE.equals(value.getEnabled())) linkUsers(value);
  }

  @Transactional(rollbackFor = Exception.class)
  public void status(Long id, boolean enabled) { RepositoryCatalog value=get(id); mapper.deleteUserRelations(id); mapper.updateStatus(id, enabled); if(enabled)linkUsers(value); }

  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    get(id); if (mapper.countUserReference(id) > 0) throw new BusinessException(60003, "代码库已关联用户，只能停用"); mapper.logicalDelete(id);
  }

  private RepositoryCatalog value(RepositoryCatalogSaveDTO dto) {
    RepositoryCatalog value = new RepositoryCatalog(); BeanUtils.copyProperties(dto, value);
    value.setRepositoryName(dto.getRepositoryName().trim()); value.setRepositoryUrl(dto.getRepositoryUrl().trim());
    value.setApplication(dto.getApplication().trim()); value.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
    value.setOperatorUserId(AuditOperator.USER_ID); value.setOperatorUserName(AuditOperator.USER_NAME); return value;
  }

  private void linkUsers(RepositoryCatalog value) {
    mapper.insertUserRelations(value.getId(), value.getApplication(), AuditOperator.USER_ID, AuditOperator.USER_NAME);
  }

  private void validate(RepositoryCatalogSaveDTO dto, Long id) {
    if (!ApplicationOptions.contains(dto.getApplication()))
      throw new BusinessException(60004, "请选择有效应用");
    if (!dto.getRepositoryName().trim().matches("[^/\\s]+/[^/\\s]+"))
      throw new BusinessException(60005, "代码库名称必须使用xxx/xxx格式");
    if (mapper.countName(dto.getRepositoryName().trim(), id) > 0) throw new BusinessException(60002, "代码库名称已存在");
  }

  private void validateApplicationFilter(String application) {
    if (application != null && !application.trim().isEmpty() && !ApplicationOptions.contains(application))
      throw new BusinessException(60004, "应用选项不合法");
  }

  private void ensureAdmin() {
    SystemUser user = currentUser();
    if (user == null || !"ADMIN".equals(user.getRoleCode())) throw new BusinessException(60006, "仅管理员可以维护代码库");
  }

  private SystemUser currentUser() {
    SystemUser user = userMapper.findById(AuditOperator.USER_ID);
    if (user == null || !Boolean.TRUE.equals(user.getEnabled())) throw new BusinessException(60009, "当前用户不存在或已停用");
    return user;
  }

  private String limit(String value, int length) {
    if (value == null) return "未知错误"; return value.length() <= length ? value : value.substring(0, length);
  }
}
