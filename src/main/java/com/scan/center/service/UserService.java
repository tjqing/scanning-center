package com.scan.center.service;

import com.scan.center.common.ApplicationOptions;
import com.scan.center.common.AuditOperator;
import com.scan.center.common.PageResult;
import com.scan.center.dto.UserSaveDTO;
import com.scan.center.exception.BusinessException;
import com.scan.center.mapper.UserMapper;
import com.scan.center.mapper.RepositoryCatalogMapper;
import com.scan.center.model.RepositoryCatalog;
import com.scan.center.model.SystemUser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统用户管理：分页查询、增删改、启停，以及按应用同步可访问代码库关系。
 */
@Service
public class UserService {
  /** 用户表访问 */
  private final UserMapper mapper;
  /** 代码库目录访问，用于按应用同步用户可访问仓库 */
  private final RepositoryCatalogMapper repositoryMapper;
  /** 密码摘要 */
  private final PasswordHasher passwordHasher;

  /**
   * @param mapper           用户 Mapper
   * @param repositoryMapper 代码库目录 Mapper
   * @param passwordHasher   密码摘要
   */
  public UserService(UserMapper mapper, RepositoryCatalogMapper repositoryMapper, PasswordHasher passwordHasher) {
    this.mapper = mapper;
    this.repositoryMapper = repositoryMapper;
    this.passwordHasher = passwordHasher;
  }

  /**
   * 分页查询用户，并填充可访问代码库 ID/名称。
   *
   * @param keyword  关键字（用户名等），可空
   * @param roleCode 角色 ADMIN/USER，可空
   * @param enabled  启用状态，可空
   * @param page     页码（从 1 起）
   * @param size     页大小（上限 100）
   * @return 分页结果
   */
  public PageResult<SystemUser> page(String keyword, String roleCode, Boolean enabled, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    List<SystemUser> users = mapper.page(keyword, roleCode, enabled, (p - 1) * s, s);
    for (SystemUser user : users) enrich(user);
    return new PageResult<SystemUser>(users, p, s, mapper.count(keyword, roleCode, enabled));
  }

  /**
   * 按主键查询用户详情（含可访问代码库）。
   *
   * @param id 用户 ID
   * @return 用户
   * @throws BusinessException 用户不存在
   */
  public SystemUser get(Long id) {
    SystemUser user = mapper.findById(id);
    if (user == null) throw new BusinessException(50001, "用户不存在");
    enrich(user);
    return user;
  }

  /**
   * 新建用户，并按应用同步代码库关系。
   *
   * @param dto 保存入参
   * @return 新用户 ID
   * @throws BusinessException 校验失败
   */
  @Transactional(rollbackFor = Exception.class)
  public Long create(UserSaveDTO dto) {
    validate(dto, null);
    SystemUser user = new SystemUser();
    BeanUtils.copyProperties(dto, user);
    user.setUsername(dto.getUsername().trim());
    user.setApplication(dto.getApplication().trim());
    user.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
    user.setPasswordHash(passwordHasher.hash(PasswordHasher.DEFAULT_PASSWORD));
    mapper.insert(user);
    syncRepositories(user.getId(), user.getApplication());
    return user.getId();
  }

  /**
   * 更新用户，并按新应用重同步代码库关系。
   *
   * @param id  用户 ID
   * @param dto 保存入参
   * @throws BusinessException 用户不存在或校验失败
   */
  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, UserSaveDTO dto) {
    get(id);
    validate(dto, id);
    SystemUser user = new SystemUser();
    BeanUtils.copyProperties(dto, user);
    user.setId(id);
    user.setUsername(dto.getUsername().trim());
    user.setApplication(dto.getApplication().trim());
    user.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
    mapper.update(user);
    syncRepositories(id, user.getApplication());
  }

  /**
   * 启用或停用用户。
   *
   * @param id      用户 ID
   * @param enabled true=启用
   */
  @Transactional(rollbackFor = Exception.class)
  public void status(Long id, boolean enabled) {
    get(id);
    mapper.updateStatus(id, enabled);
  }

  /**
   * 逻辑删除用户，并清理代码库关系；内置 admin 不可删。
   *
   * @param id 用户 ID
   * @throws BusinessException 内置管理员或用户不存在
   */
  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    SystemUser user = get(id);
    if ("admin".equalsIgnoreCase(user.getUsername())) throw new BusinessException(50004, "内置管理员不能删除");
    mapper.deleteRepositoryRelations(id);
    mapper.logicalDelete(id);
  }

  /**
   * 校验用户名、角色、应用及唯一性。
   *
   * @param dto 入参
   * @param id  更新时的用户 ID；新建传 null
   */
  private void validate(UserSaveDTO dto, Long id) {
    if (!dto.getUsername().matches("[A-Za-z][A-Za-z0-9_.-]{2,63}"))
      throw new BusinessException(50002, "用户名须以字母开头，长度3-64位，可包含数字、点、下划线和短横线");
    if (!Arrays.asList("ADMIN", "USER").contains(dto.getRoleCode()))
      throw new BusinessException(50003, "用户角色不合法");
    if (!ApplicationOptions.contains(dto.getApplication()))
      throw new BusinessException(50007, "请选择有效应用");
    if ("ADMIN".equals(dto.getRoleCode()) && !"ALL".equals(dto.getApplication()))
      throw new BusinessException(50008, "管理员应用必须为ALL");
    if (mapper.countUsername(dto.getUsername().trim(), id) > 0)
      throw new BusinessException(50005, "用户名已存在");
  }

  /**
   * 按应用重绑用户与启用中的代码库目录关系。
   *
   * @param userId      用户 ID
   * @param application 应用编码，ADMIN 可为 ALL
   */
  private void syncRepositories(Long userId, String application) {
    mapper.deleteRepositoryRelations(userId);
    for (RepositoryCatalog repository : repositoryMapper.findEnabledByApplication(application))
      mapper.insertRepositoryRelation(userId, repository.getId(), AuditOperator.userId(), AuditOperator.userName());
  }

  /**
   * 填充用户可访问代码库 ID 列表与名称列表（展示用）。
   *
   * @param user 待填充用户
   */
  private void enrich(SystemUser user) {
    List<RepositoryCatalog> repositories =
        repositoryMapper.findEnabledByApplication(user.getApplication() == null ? "ALL" : user.getApplication());
    List<Long> ids = new ArrayList<Long>();
    List<String> names = new ArrayList<String>();
    for (RepositoryCatalog repository : repositories) {
      ids.add(repository.getId());
      names.add(repository.getRepositoryName());
    }
    user.setRepositoryIds(ids);
    user.setRepositoryNames(names);
  }
}
