package com.scan.center.service;

import com.scan.center.common.*;
import com.scan.center.dto.RepositorySaveDTO;
import com.scan.center.exception.BusinessException;
import com.scan.center.execution.MdDocumentHttpClient;
import com.scan.center.mapper.RepositoryMapper;
import com.scan.center.mapper.UserMapper;
import com.scan.center.model.CodeRepository;
import com.scan.center.model.RepositoryCatalog;
import com.scan.center.model.SystemUser;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RepositoryService {
  private final RepositoryMapper mapper;
  private final UserMapper userMapper;
  private final RepositoryCatalogService catalogService;
  private final MdDocumentHttpClient mdClient;
  private final Path root;

  public RepositoryService(RepositoryMapper mapper, UserMapper userMapper, RepositoryCatalogService catalogService,
      MdDocumentHttpClient mdClient, @Value("${scan-center.storage-root}") String root) {
    this.mapper = mapper; this.userMapper = userMapper; this.catalogService = catalogService; this.mdClient = mdClient;
    this.root = Paths.get(root).toAbsolutePath().normalize();
  }

  public PageResult<CodeRepository> page(String keyword, String type, Boolean enabled, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    List<CodeRepository> values = mapper.page(keyword, type, enabled, (p - 1) * s, s);
    return new PageResult<CodeRepository>(values, p, s, mapper.count(keyword, type, enabled));
  }

  public CodeRepository get(Long id) { return getInternal(id); }
  public CodeRepository getInternal(Long id) {
    CodeRepository value = mapper.findById(id); if (value == null) throw new BusinessException(20001, "扫描源不存在"); return value;
  }
  public List<String> mdVersions(String application) { return mdClient.versions(permittedApplication(application)); }

  @Transactional(rollbackFor = Exception.class)
  public Long create(RepositorySaveDTO dto) {
    prepare(dto); CodeRepository value = new CodeRepository(); BeanUtils.copyProperties(dto, value);
    value.setOperatorUserId(AuditOperator.USER_ID); value.setOperatorUserName(AuditOperator.USER_NAME);
    value.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled()); mapper.insert(value); return value.getId();
  }

  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, RepositorySaveDTO dto) {
    CodeRepository old = getInternal(id); prepare(dto); CodeRepository value = new CodeRepository(); BeanUtils.copyProperties(dto, value);
    value.setId(id); value.setOperatorUserId(AuditOperator.USER_ID); value.setOperatorUserName(AuditOperator.USER_NAME);
    value.setEnabled(dto.getEnabled() == null ? old.getEnabled() : dto.getEnabled()); mapper.update(value);
  }

  @Transactional(rollbackFor = Exception.class) public void status(Long id, boolean enabled) { getInternal(id); mapper.updateStatus(id, enabled); }
  @Transactional(rollbackFor = Exception.class) public void delete(Long id) { getInternal(id); if (mapper.countTaskReference(id) > 0) throw new BusinessException(20002, "扫描源已被任务引用，只能停用"); mapper.logicalDelete(id); }

  public void upload(Long id, MultipartFile file) {
    CodeRepository repository = getInternal(id);
    if (!"UPLOAD".equals(repository.getSourceType())) throw new BusinessException(20007, "只有ZIP扫描源允许上传文件");
    if (file.isEmpty()) throw new BusinessException(20003, "上传文件为空");
    String originalFileName = safeName(file.getOriginalFilename());
    if (!originalFileName.toLowerCase(Locale.ROOT).endsWith(".zip")) throw new BusinessException(20008, "资源文件必须为ZIP格式");
    try {
      Files.createDirectories(root); String name = UUID.randomUUID().toString() + ".zip"; Path target = root.resolve(name).normalize();
      if (!target.startsWith(root)) throw new BusinessException(20004, "非法文件路径"); file.transferTo(target.toFile()); mapper.updateFile(id, name, originalFileName);
    } catch (BusinessException e) { throw e; } catch (Exception e) { throw new BusinessException(20005, "文件保存失败"); }
  }

  private void prepare(RepositorySaveDTO dto) {
    if (!Arrays.asList("GIT", "UPLOAD", "HTTP").contains(dto.getSourceType())) throw new BusinessException(20011, "扫描源获取方式不合法");
    if ("GIT".equals(dto.getSourceType())) prepareGit(dto);
    else if ("UPLOAD".equals(dto.getSourceType())) prepareUpload(dto);
    else prepareMd(dto);
  }

  private void prepareGit(RepositorySaveDTO dto) {
    if (dto.getRepositoryCatalogId() == null) throw new BusinessException(20022, "请选择代码库");
    if (blank(dto.getDefaultBranch())) throw new BusinessException(20023, "请选择代码库分支");
    RepositoryCatalog catalog = catalogService.accessible(dto.getRepositoryCatalogId());
    String branch = dto.getDefaultBranch().trim();
    if (!catalogService.branches(dto.getRepositoryCatalogId()).contains(branch)) throw new BusinessException(20027, "所选分支不存在或已不可访问");
    dto.setDefaultBranch(branch);
    dto.setScanSourceType("CODE"); dto.setApplication(catalog.getApplication()); dto.setRepositoryUrl(catalog.getRepositoryUrl());
    dto.setRepositoryCode(limit(catalog.getRepositoryName(), 64)); dto.setRepositoryName(limit(catalog.getRepositoryName() + "@" + branch, 128));
    dto.setVersionNo(null);
  }

  private void prepareUpload(RepositorySaveDTO dto) {
    dto.setScanSourceType("CODE"); dto.setApplication(permittedApplication(dto.getApplication())); dto.setRepositoryCatalogId(null);
    dto.setRepositoryCode(null); dto.setRepositoryUrl(null); dto.setDefaultBranch(null); dto.setVersionNo(null);
  }

  private void prepareMd(RepositorySaveDTO dto) {
    String application = permittedApplication(dto.getApplication());
    if (blank(dto.getVersionNo()) || !dto.getVersionNo().matches("\\d{6}")) throw new BusinessException(20024, "请选择YYYYMM格式的MD版本");
    if (!mdClient.versions(application).contains(dto.getVersionNo())) throw new BusinessException(20025, "所选MD版本不在HTTP接口返回范围内");
    dto.setScanSourceType("MD"); dto.setApplication(application); dto.setRepositoryCatalogId(null); dto.setRepositoryCode(null);
    dto.setRepositoryUrl(null); dto.setDefaultBranch(null); dto.setRepositoryName(application + "@" + dto.getVersionNo());
  }

  private String permittedApplication(String requested) {
    SystemUser user = userMapper.findById(AuditOperator.USER_ID);
    if (user == null || !Boolean.TRUE.equals(user.getEnabled())) throw new BusinessException(20026, "当前登录用户不存在或已停用");
    if ("ADMIN".equals(user.getRoleCode())) {
      if (!ApplicationOptions.contains(requested)) throw new BusinessException(20016, "请选择有效应用"); return requested;
    }
    if (!ApplicationOptions.contains(user.getApplication())) throw new BusinessException(20016, "当前用户未配置有效应用"); return user.getApplication();
  }

  private String safeName(String value) { return value == null ? "upload.zip" : Paths.get(value).getFileName().toString(); }
  private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
  private String limit(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }
}
