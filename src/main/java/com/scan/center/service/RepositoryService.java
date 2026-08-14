package com.scan.center.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan.center.common.*;
import com.scan.center.dto.DetailDesignAiContext;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 扫描源（代码库/ZIP/MD 文档）领域服务。
 *负责扫描源 CRUD、ZIP 上传、按来源类型（GIT/UPLOAD/HTTP）装配保存参数及权限范围内的应用校验。
 */
@Service
public class RepositoryService {

  private static final Logger log = LoggerFactory.getLogger(RepositoryService.class);

  /** 扫描源数据访问层 */
  private final RepositoryMapper mapper;
  /** 用户数据访问层，用于应用权限校验 */
  private final UserMapper userMapper;
  /** 代码库目录服务，GIT 类型扫描源依赖其解析可访问仓库 */
  private final RepositoryCatalogService catalogService;
  /** MD 文档远程接口客户端 */
  private final MdDocumentHttpClient mdClient;
  /** 详细设计文档 AI 上下文装配服务 */
  private final DetailDesignDocumentService detailDesignDocumentService;
  /** JSON 序列化，用于 Git 多项目配置落库 */
  private final ObjectMapper json;
  /** ZIP 上传文件存储根目录（绝对路径、已 normalize） */
  private final Path root;

  /**
   * @param mapper                       扫描源 Mapper
   * @param userMapper                   用户 Mapper
   * @param catalogService               代码库目录服务
   * @param mdClient                     MD 文档 HTTP 客户端
   * @param detailDesignDocumentService  详细设计文档服务
   * @param json                         Jackson ObjectMapper
   * @param root                         配置项 scan-center.storage-root
   */
  public RepositoryService(RepositoryMapper mapper, UserMapper userMapper, RepositoryCatalogService catalogService,
      MdDocumentHttpClient mdClient, DetailDesignDocumentService detailDesignDocumentService,
      ObjectMapper json, @Value("${scan-center.storage-root}") String root) {
    this.mapper = mapper; this.userMapper = userMapper; this.catalogService = catalogService; this.mdClient = mdClient;
    this.detailDesignDocumentService = detailDesignDocumentService;
    this.json = json;
    this.root = Paths.get(root).toAbsolutePath().normalize();
  }

  /**
   * 分页查询扫描源。
   *
   * @param keyword     关键词
   * @param type        扫描源类型
   * @param application 应用筛选
   * @param versionNo   版本号筛选
   * @param enabled     启用状态
   * @param page        页码
   * @param size        每页条数，限制在 1~100
   * @return 扫描源分页结果
   */
  public PageResult<CodeRepository> page(String keyword, String type, String application, String versionNo, Boolean enabled, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    List<CodeRepository> values = mapper.page(keyword, type, application, versionNo, enabled, (p - 1) * s, s);
    return new PageResult<CodeRepository>(values, p, s, mapper.count(keyword, type, application, versionNo, enabled));
  }

  /**
   * 按主键获取扫描源（对外接口）。
   *
   * @param id 扫描源 ID
   * @return 扫描源实体
   * @throws BusinessException 扫描源不存在时抛出（20001）
   */
  public CodeRepository get(Long id) { return getInternal(id); }

  /**
   * 按主键获取扫描源（内部调用，含业务校验）。
   *
   * @param id 扫描源 ID
   * @return 扫描源实体
   * @throws BusinessException 扫描源不存在时抛出（20001）
   */
  public CodeRepository getInternal(Long id) {
    CodeRepository value = mapper.findById(id); if (value == null) throw new BusinessException(20001, "扫描源不存在"); return value;
  }

  /**
   * 查询指定应用下 MD 文档可用版本列表。
   *
   * @param documentType 文档类型（概要/详细设计）
   * @param application  应用编码
   * @return 版本号列表（YYYYMM）
   * @throws BusinessException 当前用户或应用不合法时抛出
   */
  public List<String> mdVersions(String documentType, String application) { return mdClient.versions(documentType, permittedApplication(application)); }

  /**
   * 新建扫描源；保存前按 sourceType 装配衍生字段。
   *
   * @param dto 扫描源保存参数
   * @return 新扫描源 ID
   * @throws BusinessException 参数校验或装配失败时抛出
   */
  @Transactional(rollbackFor = Exception.class)
  public Long create(RepositorySaveDTO dto) {
    prepare(dto); CodeRepository value = new CodeRepository(); BeanUtils.copyProperties(dto, value);
    value.setOperatorUserId(AuditOperator.userId()); value.setOperatorUserName(AuditOperator.userName());
    value.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled()); mapper.insert(value); return value.getId();
  }

  /**
   * 更新扫描源。
   *
   * @param id  扫描源 ID
   * @param dto 扫描源保存参数
   * @throws BusinessException 扫描源不存在或参数校验失败
   */
  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, RepositorySaveDTO dto) {
    CodeRepository old = getInternal(id); prepare(dto); CodeRepository value = new CodeRepository(); BeanUtils.copyProperties(dto, value);
    value.setId(id); value.setOperatorUserId(AuditOperator.userId()); value.setOperatorUserName(AuditOperator.userName());
    value.setEnabled(dto.getEnabled() == null ? old.getEnabled() : dto.getEnabled()); mapper.update(value);
  }

  /**
   * 启用或停用扫描源。
   *
   * @param id      扫描源 ID
   * @param enabled 目标启用状态
   * @throws BusinessException 扫描源不存在时抛出（20001）
   */
  @Transactional(rollbackFor = Exception.class) public void status(Long id, boolean enabled) { getInternal(id); mapper.updateStatus(id, enabled); }

  /**
   * 逻辑删除扫描源；已被任务引用时不允许删除。
   *
   * @param id 扫描源 ID
   * @throws BusinessException 扫描源不存在（20001）或已被任务引用（20002）
   */
  @Transactional(rollbackFor = Exception.class) public void delete(Long id) { getInternal(id); if (mapper.countTaskReference(id) > 0) throw new BusinessException(20002, "扫描源已被任务引用，只能停用"); mapper.logicalDelete(id); }

  /**
   * 为 UPLOAD 类型扫描源上传 ZIP 资源包。
   *
   * @param id   扫描源 ID
   * @param file ZIP 文件
   * @throws BusinessException 非 UPLOAD 类型、文件为空/非 ZIP、路径非法或保存失败
   */
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

  /**
   * 按 sourceType 分发至 GIT/UPLOAD/HTTP 各自的装配逻辑。
   *
   * @param dto 待装配的保存参数
   * @throws BusinessException 来源类型不合法或子流程校验失败
   */
  private void prepare(RepositorySaveDTO dto) {
    if (!Arrays.asList("GIT", "UPLOAD", "HTTP").contains(dto.getSourceType())) throw new BusinessException(20011, "扫描源获取方式不合法");
    if ("GIT".equals(dto.getSourceType())) prepareGit(dto);
    else if ("UPLOAD".equals(dto.getSourceType())) prepareUpload(dto);
    else prepareMd(dto);
  }

  /**
   * 装配 GIT 类型扫描源：校验代码库与分支，汇总多项目 JSON 及展示名称。
   *
   * @param dto 保存参数，方法内写入 scanSourceType、gitProjectsJson 等衍生字段
   * @throws BusinessException 代码库、分支或版本配置不合法
   */
  private void prepareGit(RepositorySaveDTO dto) {
    List<Long> ids = dto.getRepositoryCatalogIds();
    if ((ids == null || ids.isEmpty()) && dto.getRepositoryCatalogId() != null) ids = Collections.singletonList(dto.getRepositoryCatalogId());
    if (ids == null || ids.isEmpty()) throw new BusinessException(20022, "请至少选择一个代码库");
    if (ids.size() > 100) throw new BusinessException(20028, "一次最多拉取100个项目");
    if (blank(dto.getDefaultBranch())) throw new BusinessException(20023, "请输入代码库分支");
    String branch = dto.getDefaultBranch().trim();
    if (!branch.matches("[A-Za-z0-9._/-]{1,128}") || branch.contains("..") || branch.startsWith("/") || branch.endsWith("/"))
      throw new BusinessException(20027, "分支格式不合法");
    LinkedHashMap<Long, RepositoryCatalog> catalogs = new LinkedHashMap<Long, RepositoryCatalog>();
    for (Long id : ids) if (id != null) catalogs.put(id, catalogService.accessible(id));
    if (catalogs.isEmpty()) throw new BusinessException(20022, "请至少选择一个代码库");
    List<CodeRepository.GitProject> projects = new ArrayList<CodeRepository.GitProject>();
    LinkedHashSet<String> applications = new LinkedHashSet<String>();
    LinkedHashSet<String> versions = new LinkedHashSet<String>();
    for (RepositoryCatalog catalog : catalogs.values()) {
      CodeRepository.GitProject project = new CodeRepository.GitProject(); project.setId(catalog.getId());
      project.setName(catalog.getRepositoryName()); project.setUrl(catalog.getRepositoryUrl()); project.setApplication(catalog.getApplication());
      projects.add(project); applications.add(catalog.getApplication());
      if (!blank(catalog.getVersionNo())) versions.add(catalog.getVersionNo().trim());
      else throw new BusinessException(20033, "代码库未配置版本：" + catalog.getRepositoryName());
    }
    RepositoryCatalog first = catalogs.values().iterator().next();
    dto.setDefaultBranch(branch);
    dto.setScanSourceType("CODE"); dto.setApplication(applications.size() == 1 ? applications.iterator().next() : "ALL");
    dto.setVersionNo(versions.size() == 1 ? versions.iterator().next() : null);
    dto.setRepositoryCatalogId(first.getId()); dto.setRepositoryUrl(first.getRepositoryUrl());
    dto.setRepositoryCode(limit(projects.size() == 1 ? first.getRepositoryName() : projects.size() + "个项目", 64));
    dto.setRepositoryName(limit((projects.size() == 1 ? first.getRepositoryName() : "GIT-" + projects.size() + "个项目") + "@" + branch, 128));
    try { dto.setRepositoryCatalogIds(new ArrayList<Long>(catalogs.keySet())); dto.setGitProjectsJson(json.writeValueAsString(projects)); }
    catch (Exception e) { throw new BusinessException(20029, "Git项目配置序列化失败"); }
    dto.setMdDocumentType(null);
  }

  /**
   * 装配 UPLOAD（ZIP）类型扫描源：校验应用与 YYYYMM 版本，生成展示名称。
   *
   * @param dto 保存参数
   * @throws BusinessException 应用或版本格式不合法
   */
  private void prepareUpload(RepositorySaveDTO dto) {
    if (!ApplicationOptions.contains(dto.getApplication()) || "ALL".equals(dto.getApplication()))
      throw new BusinessException(20016, "请选择有效应用");
    if (blank(dto.getVersionNo()) || !dto.getVersionNo().trim().matches("\\d{6}"))
      throw new BusinessException(20024, "请选择YYYYMM格式版本");
    String application = permittedApplication(dto.getApplication());
    String versionNo = dto.getVersionNo().trim();
    dto.setScanSourceType("CODE");
    dto.setApplication(application);
    dto.setVersionNo(versionNo);
    dto.setRepositoryCatalogId(null);
    dto.setRepositoryCatalogIds(null);
    dto.setGitProjectsJson(null);
    dto.setRepositoryCode(limit("ZIP-" + application, 64));
    dto.setRepositoryName(limit("ZIP@" + application + "@" + versionNo, 128));
    dto.setRepositoryUrl(null);
    dto.setDefaultBranch(null);
    dto.setMdDocumentType(null);
  }

  /**
   * 装配 HTTP（MD 文档）类型扫描源：校验文档类型、版本及远程可用性。
   *
   * @param dto 保存参数
   * @throws BusinessException 文档类型、版本或远程校验失败
   */
  private void prepareMd(RepositorySaveDTO dto) {
    String application = permittedApplication(dto.getApplication());
    if (!Arrays.asList(MdDocumentHttpClient.OVERVIEW, MdDocumentHttpClient.DETAIL).contains(dto.getMdDocumentType()))
      throw new BusinessException(20030, "请选择概要设计.md或详细设计.md");
    if (blank(dto.getVersionNo()) || !dto.getVersionNo().matches("\\d{6}")) throw new BusinessException(20024, "请选择YYYYMM格式的MD版本");
    if (MdDocumentHttpClient.DETAIL.equals(dto.getMdDocumentType())) {
      // 详细设计：创建/保存扫描源时装配 AI 上下文（桩方法，后续补真实查库与落盘）
      DetailDesignAiContext detailCtx = detailDesignDocumentService.buildAiContext(application, dto.getVersionNo().trim());
      log.info("详细设计文档装配 application={}, versionNo={}, found={}, message={}, subitems={}",
          application, dto.getVersionNo(), detailCtx.isFound(), detailCtx.getMessage(),
          detailCtx.getSubitemNos() == null ? 0 : detailCtx.getSubitemNos().size());
      // TODO: found=false 时提示「数据库没有」，由前端弹框/iframe 引导下载后上传 md
    } else if (!mdClient.versions(dto.getMdDocumentType(), application).contains(dto.getVersionNo())) {
      throw new BusinessException(20025, "所选MD版本不在接口返回范围内");
    }
    dto.setScanSourceType("MD"); dto.setApplication(application); dto.setRepositoryCatalogId(null); dto.setRepositoryCode(null);
    dto.setRepositoryCatalogIds(null); dto.setGitProjectsJson(null); dto.setRepositoryUrl(null); dto.setDefaultBranch(null);
    String typeName = MdDocumentHttpClient.OVERVIEW.equals(dto.getMdDocumentType()) ? "概要设计.md" : "详细设计.md";
    dto.setRepositoryName(limit(typeName + "-" + application + "@" + dto.getVersionNo(), 128));
  }

  /**
   * 按当前登录用户角色解析允许操作的应用编码；非管理员固定为其所属应用。
   *
   * @param requested 请求中的应用编码
   * @return 实际可用的应用编码
   * @throws BusinessException 用户无效或应用不合法
   */
  private String permittedApplication(String requested) {
    SystemUser user = userMapper.findById(AuditOperator.userId());
    if (user == null || !Boolean.TRUE.equals(user.getEnabled())) throw new BusinessException(20026, "当前登录用户不存在或已停用");
    if ("ADMIN".equals(user.getRoleCode())) {
      if (!ApplicationOptions.contains(requested)) throw new BusinessException(20016, "请选择有效应用"); return requested;
    }
    if (!ApplicationOptions.contains(user.getApplication())) throw new BusinessException(20016, "当前用户未配置有效应用"); return user.getApplication();
  }

  /** 提取上传文件安全文件名，null 时默认 upload.zip。 */
  private String safeName(String value) { return value == null ? "upload.zip" : Paths.get(value).getFileName().toString(); }

  /** 判断字符串是否为 null 或空白。 */
  private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

  /** 截断字符串至指定最大长度。 */
  private String limit(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }
}
