package com.scan.center.service;

import com.scan.center.common.*;
import com.scan.center.dto.RepositorySaveDTO;
import com.scan.center.exception.BusinessException;
import com.scan.center.mapper.RepositoryMapper;
import com.scan.center.model.CodeRepository;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RepositoryService {
  private final RepositoryMapper mapper;
  private final Path root;

  public RepositoryService(
      RepositoryMapper mapper, @Value("${scan-center.storage-root}") String root) {
    this.mapper = mapper;
    this.root = Paths.get(root).toAbsolutePath().normalize();
  }

  public PageResult<CodeRepository> page(
      String keyword, String type, Boolean enabled, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    java.util.List<CodeRepository> values = mapper.page(keyword, type, enabled, (p - 1) * s, s);
    for (CodeRepository value : values) maskSecrets(value);
    return new PageResult<CodeRepository>(
        values,
        p,
        s,
        mapper.count(keyword, type, enabled));
  }

  public CodeRepository get(Long id) {
    CodeRepository v = mapper.findById(id);
    if (v == null) throw new BusinessException(20001, "资源库不存在");
    maskSecrets(v);
    return v;
  }

  public CodeRepository getInternal(Long id) {
    CodeRepository v = mapper.findById(id);
    if (v == null) throw new BusinessException(20001, "资源库不存在");
    return v;
  }

  @Transactional(rollbackFor = Exception.class)
  public Long create(RepositorySaveDTO dto) {
    validate(dto);
    CodeRepository v = new CodeRepository();
    BeanUtils.copyProperties(dto, v);
    v.setOperatorUserId(AuditOperator.USER_ID);
    v.setOperatorUserName(AuditOperator.USER_NAME);
    v.setEncryptedToken(dto.getToken());
    v.setEncryptedDatabasePassword(dto.getDatabasePassword());
    v.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
    mapper.insert(v);
    return v.getId();
  }

  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, RepositorySaveDTO dto) {
    CodeRepository old = getInternal(id);
    validate(dto);
    CodeRepository v = new CodeRepository();
    BeanUtils.copyProperties(dto, v);
    v.setOperatorUserId(AuditOperator.USER_ID);
    v.setOperatorUserName(AuditOperator.USER_NAME);
    v.setId(id);
    v.setEncryptedToken(
        dto.getToken() == null || dto.getToken().isEmpty()
            ? old.getEncryptedToken()
            : dto.getToken());
    v.setEncryptedDatabasePassword(
        dto.getDatabasePassword() == null || dto.getDatabasePassword().isEmpty()
            ? old.getEncryptedDatabasePassword()
            : dto.getDatabasePassword());
    v.setEnabled(dto.getEnabled() == null ? old.getEnabled() : dto.getEnabled());
    mapper.update(v);
  }

  @Transactional(rollbackFor = Exception.class)
  public void status(Long id, boolean enabled) {
    getInternal(id);
    mapper.updateStatus(id, enabled);
  }

  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    getInternal(id);
    if (mapper.countTaskReference(id) > 0) throw new BusinessException(20002, "资源库已被任务引用，只能停用");
    mapper.logicalDelete(id);
  }

  public void upload(Long id, MultipartFile file) {
    CodeRepository repository = getInternal(id);
    if (!"UPLOAD".equals(repository.getSourceType())) {
      throw new BusinessException(20007, "只有上传文件类型的资源库允许上传ZIP");
    }
    if (file.isEmpty()) throw new BusinessException(20003, "上传文件为空");
    String originalFileName = safeName(file.getOriginalFilename());
    if (!originalFileName.toLowerCase().endsWith(".zip")) {
      throw new BusinessException(20008, "资源文件必须为ZIP格式");
    }
    try {
      Files.createDirectories(root);
      String name = UUID.randomUUID().toString() + extension(file.getOriginalFilename());
      Path target = root.resolve(name).normalize();
      if (!target.startsWith(root)) throw new BusinessException(20004, "非法文件路径");
      file.transferTo(target.toFile());
      mapper.updateFile(id, name, originalFileName);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(20005, "文件保存失败");
    }
  }

  private void validate(RepositorySaveDTO dto) {
    if (!java.util.Arrays.asList("CODE", "MD").contains(dto.getScanSourceType()))
      throw new BusinessException(20018, "扫描内容类型必须为CODE或MD");
    if (!java.util.Arrays.asList("GIT", "UPLOAD", "DATABASE").contains(dto.getSourceType()))
      throw new BusinessException(20011, "扫描源类型不合法");
    if ("MD".equals(dto.getScanSourceType()) && !"DATABASE".equals(dto.getSourceType()))
      throw new BusinessException(20019, "MD扫描源只能从数据库获取");
    if ("CODE".equals(dto.getScanSourceType()) && "DATABASE".equals(dto.getSourceType()))
      throw new BusinessException(20020, "CODE扫描源仅支持Git拉取或ZIP上传");
    validateCommaList(dto.getFileTypes(), "扫描文件类型", "[A-Za-z0-9]+(?:[._+-][A-Za-z0-9]+)*");
    validateCommaList(dto.getExcludePatterns(), "排除目录", "[^,，;；\\s]+");
    validateCommaList(dto.getScanPaths(), "扫描路径", "[^,，;；\\s]+");
    if ("GIT".equals(dto.getSourceType())
        && (dto.getRepositoryUrl() == null || dto.getRepositoryUrl().trim().isEmpty()))
      throw new BusinessException(20006, "Git资源库必须填写仓库地址");
    if ("GIT".equals(dto.getSourceType())) {
      if (blank(dto.getRepositoryCode()) || !dto.getRepositoryCode().matches("[A-Za-z0-9_-]+"))
        throw new BusinessException(20015, "Git代码库必须填写合法的代码库编码");
      if (blank(dto.getApplication())) throw new BusinessException(20016, "Git代码库必须配置所属应用");
    }
    if ("DATABASE".equals(dto.getSourceType())) {
      if (blank(dto.getApplication())) throw new BusinessException(20016, "MD数据库扫描源必须配置所属应用");
      if (blank(dto.getDocumentQuery())
          || blank(dto.getDocumentNameColumn()) || blank(dto.getDocumentContentColumn()))
        throw new BusinessException(20009, "数据库文档源必须填写查询SQL、名称字段和内容字段");
      String sql = dto.getDocumentQuery().trim().toLowerCase(java.util.Locale.ROOT);
      if (!sql.startsWith("select") || sql.contains(";"))
        throw new BusinessException(20010, "文档查询仅允许单条SELECT语句");
      if (!dto.getDocumentQuery().contains(":application") || !dto.getDocumentQuery().contains(":version"))
        throw new BusinessException(20021, "MD文档查询必须包含:application和:version参数");
      validateColumnName(dto.getDocumentNameColumn(), "名称字段");
      validateColumnName(dto.getDocumentContentColumn(), "内容字段");
      if (!blank(dto.getDocumentTypeColumn())) validateColumnName(dto.getDocumentTypeColumn(), "类型字段");
    }
  }

  private void validateCommaList(String value, String name, String tokenPattern) {
    if (blank(value)) return;
    if (value.contains("，") || value.contains(";") || value.contains("；")
        || value.startsWith(",") || value.endsWith(",") || value.contains(",,"))
      throw new BusinessException(20013, name + "必须使用英文逗号分隔，且不能有空项");
    for (String token : value.split(","))
      if (!token.trim().matches(tokenPattern))
        throw new BusinessException(20013, name + "中包含非法项：" + token.trim());
  }

  private void validateColumnName(String value, String name) {
    if (!value.trim().matches("[A-Za-z_][A-Za-z0-9_]*"))
      throw new BusinessException(20014, name + "必须是合法的数据库字段名");
  }

  private boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private void maskSecrets(CodeRepository value) {
    value.setEncryptedToken(value.getEncryptedToken() == null ? null : "******");
    value.setEncryptedDatabasePassword(
        value.getEncryptedDatabasePassword() == null ? null : "******");
  }

  private String safeName(String n) {
    return n == null ? "upload" : Paths.get(n).getFileName().toString();
  }

  private String extension(String n) {
    if (n == null) return ".bin";
    int i = n.lastIndexOf('.');
    return i < 0 ? ".bin" : n.substring(i).replaceAll("[^A-Za-z0-9.]", "");
  }
}
