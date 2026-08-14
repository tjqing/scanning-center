package com.scan.center.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan.center.exception.BusinessException;
import com.scan.center.model.CodeRepository;
import com.scan.center.model.RepositoryCatalog;
import com.scan.center.service.RepositoryCatalogService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 扫描源工作区准备服务。
 *
 * 负责为单次扫描任务创建并发布快照工作区，支持以下扫描源类型：
 *
 *Git：通过 SSH（系统 git + {@code GIT_SSH_COMMAND}）或 HTTPS（JGit / 命令行）拉取代码
 *ZIP 上传：从本地存储解压到工作区
 *MD 文档：通过 HTTP 下载 Markdown 文档到工作区
 *
 *
 * 与 {@link RepositoryCatalogService#decryptSecret(RepositoryCatalog)} 协作获取代码库凭据：
 * SSH 私钥或 HTTPS Token 均经该方法解密后再使用。主要调用点包括
 * {@link #runGitWithAuth}、{@link #cloneWithSystemGitSsh}、{@link #applyHttpsOrFallbackAuth}。
 *
 * SSH 认证流程要点：
 *
 *{@link #resolveCloneUrl} 在 SSH 认证且地址仍为 HTTPS 时，自动执行 HTTPS→SSH 转换（{@link #httpsToSshUrl}）
 *解密后的私钥写入临时文件（前缀 {@code scan-center-ssh-}），并通过 {@link #hardenKeyFilePermissions} 收紧权限
 *通过环境变量 {@code GIT_SSH_COMMAND} 指定 {@code ssh -i <keyPath>}，强制使用临时私钥并跳过主机密钥校验
 *
 */
@Service
public class SourceWorkspaceService {

  /** 日志记录器。 */
  private static final Logger log = LoggerFactory.getLogger(SourceWorkspaceService.class);

  /** 上传文件（如 ZIP）的本地存储根目录。 */
  private final Path storageRoot;

  /** 快照工作区根目录，其下按 snapshotId 分子目录。 */
  private final Path snapshotRoot;

  /** 全局 Git HTTPS 认证用户名（配置项 fallback）。 */
  private final String gitUsername;

  /** 全局 Git HTTPS 认证 Token（配置项 fallback）。 */
  private final String gitToken;

  /** MD 文档 HTTP 下载客户端。 */
  private final MdDocumentHttpClient mdClient;

  /** JSON 序列化/反序列化工具。 */
  private final ObjectMapper json;

  /** 代码库目录服务，用于加载凭据并调用 {@link RepositoryCatalogService#decryptSecret}。 */
  private final RepositoryCatalogService catalogService;

  /**
   * 构造扫描源工作区服务。
   *
   * @param storageRoot   上传文件存储根路径（配置 {@code scan-center.storage-root}）
   * @param workspaceRoot 工作区根路径（配置 {@code scan-center.workspace-root}），快照目录为其下的 {@code snapshots}
   * @param gitUsername   全局 Git HTTPS 用户名，可为空
   * @param gitToken      全局 Git HTTPS Token，可为空
   * @param mdClient      MD 文档下载客户端
   * @param json          Jackson ObjectMapper
   * @param catalogService 代码库目录服务
   */
  public SourceWorkspaceService(
      @Value("${scan-center.storage-root}") String storageRoot,
      @Value("${scan-center.workspace-root}") String workspaceRoot,
      @Value("${scan-center.git.username:}") String gitUsername,
      @Value("${scan-center.git.token:}") String gitToken,
      MdDocumentHttpClient mdClient, ObjectMapper json, RepositoryCatalogService catalogService) {
    this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    this.snapshotRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize().resolve("snapshots");
    this.gitUsername = gitUsername == null ? "" : gitUsername;
    this.gitToken = gitToken == null ? "" : gitToken;
    this.mdClient = mdClient;
    this.json = json;
    this.catalogService = catalogService;
  }

  /**
   * 为指定快照准备扫描工作区并发布为 {@code current} 目录。
   *
   * 根据 {@link CodeRepository#getScanSourceType()} 分发到 MD 下载或 Git/ZIP 代码准备流程；
   * Git 拉取过程中会通过 {@code catalogService.decryptSecret} 获取 SSH 私钥或 HTTPS Token。
   *
   * @param snapshotId  快照 ID，用于隔离工作区目录
   * @param repository  扫描源配置（类型、地址、项目列表等）
   * @param versionNo   MD 扫描源的版本号（YYYYMM），非 MD 类型可忽略
   * @return 已发布的工作区路径（{@code .../snapshots/{snapshotId}/current}）
   * @throws BusinessException 目录越界（30020）、准备失败（30021）等业务异常
   */
  public Path prepare(Long snapshotId, CodeRepository repository, String versionNo) {
    Path base = snapshotRoot.resolve(String.valueOf(snapshotId)).normalize();
    Path staging = base.resolve("staging-" + UUID.randomUUID()).normalize();
    Path current = base.resolve("current").normalize();
    if (!base.startsWith(snapshotRoot)) throw new BusinessException(30020, "任务快照目录越界");
    try {
      Files.createDirectories(staging);
      if ("MD".equals(repository.getScanSourceType())) prepareMd(repository, versionNo, staging);
      else prepareCode(repository, staging);
      if (!containsRegularFile(staging)) throw new IOException("扫描源中没有可扫描文件");
      publishWorkspace(staging, current);
      return current;
    } catch (BusinessException e) {
      log.warn("扫描源准备业务失败 snapshotId={}, msg={}", snapshotId, e.getMessage());
      safeDelete(staging);
      throw e;
    } catch (Exception e) {
      log.error("扫描源准备异常 snapshotId={}, staging={}, current={}", snapshotId, staging, current, e);
      safeDelete(staging);
      throw new BusinessException(30021, "扫描源准备失败：" + limit(e.getClass().getSimpleName() + ": " + e.getMessage(), 1000));
    }
  }

  /**
   * 将 staging 目录原子发布为 current 工作区。
   *
   * Windows 上对目录 rename 经常 AccessDenied。
   * 优先 {@link Files#move}；失败则清空 current 后整树 copy，再删 staging。
   *
   * @param staging 临时准备目录
   * @param current 最终工作区目录
   * @throws IOException 文件系统操作失败
   */
  private void publishWorkspace(Path staging, Path current) throws IOException {
    if (Files.exists(current)) deleteTree(current);
    try {
      Files.move(staging, current);
      return;
    } catch (IOException moveFailed) {
      log.warn("工作区 rename 失败，回退为 copy：{} -> {}，原因：{}", staging, current, moveFailed.toString());
    }
    if (Files.exists(current)) deleteTree(current);
    Files.createDirectories(current);
    copyTree(staging, current);
    safeDelete(staging);
  }

  /**
   * 递归复制目录树，保留文件属性。
   *
   * @param source 源目录
   * @param target 目标目录
   * @throws IOException 复制过程中 I/O 失败
   */
  private void copyTree(Path source, Path target) throws IOException {
    Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
      /** 在访问子目录前于目标侧创建对应目录。 */
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        Files.createDirectories(target.resolve(source.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      /** 复制单个普通文件到目标目录。 */
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Path dest = target.resolve(source.relativize(file).toString());
        Files.createDirectories(dest.getParent());
        Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /**
   * 准备 CODE 类型扫描源：Git clone 或 ZIP 解压。
   *
   * @param repository 扫描源配置
   * @param target     目标工作区目录
   * @throws Exception Git 拉取或 ZIP 解压失败
   */
  private void prepareCode(CodeRepository repository, Path target) throws Exception {
    if ("GIT".equals(repository.getSourceType())) {
      cloneRepositories(repository, target);
    } else if ("UPLOAD".equals(repository.getSourceType())) {
      if (blank(repository.getStorageKey())) throw new IOException("ZIP扫描源尚未上传文件");
      Path zip = storageRoot.resolve(repository.getStorageKey()).normalize();
      if (!zip.startsWith(storageRoot) || !Files.isRegularFile(zip)) throw new IOException("ZIP源文件不存在");
      ZipExtractor.extract(zip, target);
    } else {
      throw new IOException("CODE扫描源只支持Git或ZIP：" + repository.getSourceType());
    }
  }

  /**
   * 准备 MD 类型扫描源：通过 HTTP 下载指定版本文档。
   *
   * @param repository 扫描源配置（需含 application、mdDocumentType）
   * @param versionNo  版本号，格式 YYYYMM
   * @param target     目标工作区目录
   * @throws Exception 参数校验或下载失败
   */
  private void prepareMd(CodeRepository repository, String versionNo, Path target) throws Exception {
    if (!"HTTP".equals(repository.getSourceType())) throw new IOException("MD扫描源必须使用HTTP获取方式");
    if (blank(repository.getApplication())) throw new IOException("MD扫描源未配置所属应用");
    if (blank(versionNo) || !versionNo.matches("\\d{6}")) throw new IOException("MD扫描版本必须为YYYYMM格式");
    mdClient.download(repository.getMdDocumentType(), repository.getApplication(), versionNo, target);
  }

  /**
   * 任务启动前增量同步：对 GIT 扫描源比对远端 tip 与本地 HEAD。
   *
   * tip 相同则跳过；不同则 fetch + hard reset，失败则删目录重 clone。
   * 远端查询与 fetch 均通过 {@link #runGitWithAuth} 完成凭据注入（含 {@code decryptSecret}）。
   *
   * @param currentRoot 已发布的快照工作区根目录（{@code current}）
   * @param repository  扫描源配置
   * @return {@code true} 表示工作区至少有一个项目发生了更新
   * @throws BusinessException 工作区不存在（30021）、目录越界（30020）或同步失败（30021）
   */
  public boolean syncGitIfNeeded(Path currentRoot, CodeRepository repository) {
    if (currentRoot == null || repository == null || !"GIT".equals(repository.getSourceType())) return false;
    Path root = currentRoot.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) throw new BusinessException(30021, "快照工作区不存在：" + root);
    try {
      List<CodeRepository.GitProject> projects = blank(repository.getGitProjectsJson())
          ? Collections.singletonList(legacyProject(repository))
          : json.readValue(repository.getGitProjectsJson(), new TypeReference<List<CodeRepository.GitProject>>() {});
      if (projects.isEmpty()) return false;
      boolean updated = false;
      Set<String> directories = new HashSet<String>();
      String branch = repository.getDefaultBranch();
      for (CodeRepository.GitProject project : projects) {
        String directory = safeDirectory(project.getName());
        if (!directories.add(directory)) directory += "-" + project.getId();
        Path projectDir = root.resolve(directory).normalize();
        if (!projectDir.startsWith(root)) throw new BusinessException(30020, "项目目录越界：" + directory);
        RepositoryCatalog catalog = catalogService.loadForClone(project.getId());
        String cloneUrl = resolveCloneUrl(project.getUrl(), catalog);
        String remoteTip = resolveRemoteTip(cloneUrl, branch, catalog);
        String localHead = localHead(projectDir);
        if (!blank(remoteTip) && remoteTip.equalsIgnoreCase(nullToEmpty(localHead))) {
          log.info("Git tip 未变，跳过同步 project={}, tip={}", directory, shortSha(remoteTip));
          continue;
        }
        log.info("Git tip 变更，开始同步 project={}, local={}, remote={}",
            directory, shortSha(localHead), shortSha(remoteTip));
        if (Files.isDirectory(projectDir.resolve(".git"))) {
          try {
            fetchAndReset(projectDir, cloneUrl, branch, catalog);
            updated = true;
            continue;
          } catch (Exception e) {
            log.warn("增量 fetch 失败，回退为重 clone project={}, err={}", directory, e.toString());
            safeDelete(projectDir);
          }
        } else if (Files.exists(projectDir)) {
          safeDelete(projectDir);
        }
        cloneRepository(cloneUrl, branch, projectDir, catalog);
        updated = true;
      }
      return updated;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(30021, "Git 增量同步失败：" + limit(e.getClass().getSimpleName() + ": " + e.getMessage(), 1000));
    }
  }

  /**
   * 读取本地 Git 仓库当前 HEAD 的 commit SHA。
   *
   * @param projectDir 项目目录（含 {@code .git}）
   * @return HEAD 的 SHA，非 Git 目录或读取失败时返回 {@code null}
   */
  private String localHead(Path projectDir) {
    if (!Files.isDirectory(projectDir.resolve(".git"))) return null;
    try {
      String out = runGit(projectDir, null, Arrays.asList("rev-parse", "HEAD"));
      return blank(out) ? null : out.trim().split("\\s+")[0];
    } catch (Exception e) {
      log.warn("读取本地 HEAD 失败 dir={}, err={}", projectDir, e.toString());
      return null;
    }
  }

  /**
   * 通过 {@code git ls-remote} 解析远端指定分支的最新 commit SHA。
   *
   * @param url     克隆地址（可能已 HTTPS→SSH 转换）
   * @param branch  分支名，为空时使用 HEAD
   * @param catalog 代码库凭据
   * @return 远端 tip SHA，无法解析时返回 {@code null}
   * @throws Exception git 命令执行失败
   */
  private String resolveRemoteTip(String url, String branch, RepositoryCatalog catalog) throws Exception {
    String ref = blank(branch) ? "HEAD" : branch;
    List<String> cmd = new ArrayList<String>();
    cmd.add("ls-remote");
    cmd.add(url);
    cmd.add(ref);
    cmd.add("refs/heads/" + ref);
    String out = runGitWithAuth(null, url, catalog, cmd);
    if (blank(out)) return null;
    String exact = null;
    String first = null;
    for (String line : out.split("\n")) {
      String t = line.trim();
      if (t.isEmpty()) continue;
      String[] parts = t.split("\\s+");
      if (parts.length < 2) continue;
      if (first == null) first = parts[0];
      if (("refs/heads/" + ref).equals(parts[1]) || ref.equals(parts[1])) {
        exact = parts[0];
        break;
      }
    }
    return exact != null ? exact : first;
  }

  /**
   * 对已存在的本地仓库执行 fetch 并 hard reset 到 FETCH_HEAD。
   *
   * @param projectDir 本地项目目录
   * @param url        远端地址
   * @param branch     分支名
   * @param catalog    代码库凭据
   * @throws Exception fetch 或 reset 失败
   */
  private void fetchAndReset(Path projectDir, String url, String branch, RepositoryCatalog catalog) throws Exception {
    String ref = blank(branch) ? "HEAD" : branch;
    runGitWithAuth(projectDir, url, catalog, Arrays.asList("remote", "set-url", "origin", url));
    List<String> fetch = new ArrayList<String>();
    fetch.add("fetch");
    fetch.add("--depth");
    fetch.add("1");
    fetch.add("origin");
    fetch.add(ref);
    runGitWithAuth(projectDir, url, catalog, fetch);
    runGit(projectDir, null, Arrays.asList("reset", "--hard", "FETCH_HEAD"));
    log.info("Git fetch+reset 完成 dir={}, head={}", projectDir.getFileName(), shortSha(localHead(projectDir)));
  }

  /**
   * 执行带认证的 git 子命令（系统 git 进程）。
   *
   * 凭据处理：
   *
   *SSH：调用 {@link RepositoryCatalogService#decryptSecret} 解密私钥 →
   *       写入临时文件 → 设置 {@code GIT_SSH_COMMAND=ssh -i "<keyPath>" ...}
   *HTTPS：调用 {@code decryptSecret} 解密 Token → {@link #injectHttpsCredentials} 注入 URL
   *全局 fallback：未配置代码库凭据时使用 {@code gitUsername/gitToken}
   *
   * 临时 SSH 私钥文件在 {@code finally} 块中删除。
   *
   * @param workDir  git 工作目录，可为 {@code null}（如 ls-remote）
   * @param url      仓库 URL，用于匹配命令参数中的地址并注入凭据
   * @param catalog  代码库凭据配置
   * @param gitArgs  git 子命令及参数（不含 {@code git} 前缀）
   * @return 命令标准输出（已 trim）
   * @throws Exception git 非零退出或 I/O 失败
   */
  private String runGitWithAuth(Path workDir, String url, RepositoryCatalog catalog, List<String> gitArgs) throws Exception {
    String authType = catalog == null || blank(catalog.getAuthType())
        ? "" : catalog.getAuthType().trim().toUpperCase(Locale.ROOT);
    Path keyFile = null;
    Map<String, String> env = new HashMap<String, String>();
    List<String> args = new ArrayList<String>(gitArgs);
    try {
      if (RepositoryCatalogService.AUTH_SSH.equals(authType)) {
        String secret = catalogService.decryptSecret(catalog);
        if (blank(secret)) throw new BusinessException(30022, "代码库未配置 SSH 私钥：" + safeName(catalog));
        keyFile = Files.createTempFile("scan-center-ssh-", ".key");
        Files.write(keyFile, normalizePrivateKey(secret).getBytes(StandardCharsets.UTF_8));
        hardenKeyFilePermissions(keyFile);
        String keyPath = keyFile.toAbsolutePath().toString();
        env.put("GIT_SSH_COMMAND", "ssh -i \"" + keyPath + "\" -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile="
            + (isWindows() ? "NUL" : "/dev/null"));
      } else if (RepositoryCatalogService.AUTH_HTTPS.equals(authType)) {
        String secret = catalogService.decryptSecret(catalog);
        if (blank(secret)) throw new BusinessException(30022, "代码库未配置访问 Token：" + safeName(catalog));
        String user = blank(catalog.getGitUsername()) ? "token" : catalog.getGitUsername().trim();
        String authed = injectHttpsCredentials(url, user, secret);
        for (int i = 0; i < args.size(); i++) {
          if (url.equals(args.get(i))) args.set(i, authed);
        }
      } else if (!gitToken.isEmpty() && isHttpUrl(url)) {
        String authed = injectHttpsCredentials(url, gitUsername.isEmpty() ? "token" : gitUsername, gitToken);
        for (int i = 0; i < args.size(); i++) {
          if (url.equals(args.get(i))) args.set(i, authed);
        }
      }
      return runGit(workDir, env, args);
    } finally {
      if (keyFile != null) {
        try { Files.deleteIfExists(keyFile); } catch (Exception ignored) {}
      }
    }
  }

  /**
   * 将用户名与 Token 嵌入 HTTPS URL（{@code https://user:pass@host/path}）。
   *
   * @param httpsUrl 原始 HTTPS 地址
   * @param username 用户名（GitHub 等常用 {@code token}）
   * @param token    访问 Token 或密码
   * @return 带 embedded credentials 的 URL
   * @throws BusinessException 构造失败（30022）
   */
  private String injectHttpsCredentials(String httpsUrl, String username, String token) {
    try {
      java.net.URI uri = java.net.URI.create(httpsUrl);
      String user = java.net.URLEncoder.encode(username, "UTF-8");
      String pass = java.net.URLEncoder.encode(token, "UTF-8");
      String path = uri.getRawPath() == null ? "" : uri.getRawPath();
      String query = uri.getRawQuery() == null ? "" : ("?" + uri.getRawQuery());
      return uri.getScheme() + "://" + user + ":" + pass + "@" + uri.getHost()
          + (uri.getPort() > 0 ? (":" + uri.getPort()) : "") + path + query;
    } catch (Exception e) {
      throw new BusinessException(30022, "构造 HTTPS 认证地址失败");
    }
  }

  /**
   * 启动系统 git 子进程并等待完成。
   *
   * @param workDir   工作目录，可为 {@code null}
   * @param extraEnv  额外环境变量（如 {@code GIT_SSH_COMMAND}）
   * @param gitArgs   git 子命令及参数
   * @return 合并后的标准输出
   * @throws Exception 非零退出码或进程 I/O 失败
   */
  private String runGit(Path workDir, Map<String, String> extraEnv, List<String> gitArgs) throws Exception {
    List<String> cmd = new ArrayList<String>();
    cmd.add("git");
    cmd.addAll(gitArgs);
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    if (workDir != null) pb.directory(workDir.toFile());
    if (extraEnv != null && !extraEnv.isEmpty()) pb.environment().putAll(extraEnv);
    Process process = pb.start();
    String output = readProcessOutput(process);
    int code = process.waitFor();
    if (code != 0) {
      throw new IOException("git " + gitArgs + " failed(exit=" + code + "): " + limit(output, 500));
    }
    return output == null ? "" : output.trim();
  }

  /**
   * 截短 commit SHA 用于日志展示。
   *
   * @param sha 完整 SHA
   * @return 最多 12 位的 SHA，空值返回 {@code "-"}
   */
  private String shortSha(String sha) {
    if (blank(sha)) return "-";
    return sha.length() <= 12 ? sha : sha.substring(0, 12);
  }

  /**
   * 将 {@code null} 转为空字符串。
   *
   * @param value 原字符串
   * @return 非 null 的原值或空串
   */
  private String nullToEmpty(String value) { return value == null ? "" : value; }

  /**
   * 克隆扫描源配置中的全部 Git 项目到目标目录的子文件夹。
   *
   * @param repository 扫描源配置
   * @param target     工作区根目录
   * @throws Exception 项目列表为空、地址无效或 clone 失败
   */
  private void cloneRepositories(CodeRepository repository, Path target) throws Exception {
    List<CodeRepository.GitProject> projects = blank(repository.getGitProjectsJson())
        ? Collections.singletonList(legacyProject(repository))
        : json.readValue(repository.getGitProjectsJson(), new TypeReference<List<CodeRepository.GitProject>>() {});
    if (projects.isEmpty()) throw new IOException("Git扫描源未配置项目");
    Set<String> directories = new HashSet<String>();
    for (CodeRepository.GitProject project : projects) {
      if (blank(project.getUrl()) && (project.getId() == null)) throw new IOException("Git仓库地址为空：" + project.getName());
      String directory = safeDirectory(project.getName());
      if (!directories.add(directory)) directory += "-" + project.getId();
      RepositoryCatalog catalog = catalogService.loadForClone(project.getId());
      String cloneUrl = resolveCloneUrl(project.getUrl(), catalog);
      cloneRepository(cloneUrl, repository.getDefaultBranch(), target.resolve(directory), catalog);
    }
  }

  /**
   * 解析最终用于 clone 的仓库地址。
   *
   * 优先使用代码库目录中的最新 {@code repositoryUrl}；
   * 当认证类型为 SSH 且地址仍为 {@code https://} 时，自动调用 {@link #httpsToSshUrl} 转为
   * {@code git@host:path.git}，避免「选了私钥却仍走 HTTPS」导致 CredentialsProvider 报错。
   *
   * @param projectUrl 扫描源中配置的项目 URL
   * @param catalog    代码库目录（含 authType、repositoryUrl）
   * @return 最终 clone URL
   * @throws BusinessException 地址为空、SSH/HTTPS 与地址格式不匹配（30022）
   */
  private String resolveCloneUrl(String projectUrl, RepositoryCatalog catalog) {
    String url = !blank(projectUrl) ? projectUrl.trim() : "";
    if (catalog != null && !blank(catalog.getRepositoryUrl())) url = catalog.getRepositoryUrl().trim();
    if (blank(url)) throw new BusinessException(30022, "Git仓库地址为空：" + safeName(catalog));
    String authType = catalog == null || blank(catalog.getAuthType())
        ? "" : catalog.getAuthType().trim().toUpperCase(Locale.ROOT);
    if (RepositoryCatalogService.AUTH_SSH.equals(authType) && isHttpUrl(url)) {
      String ssh = httpsToSshUrl(url);
      log.info("SSH认证检测到 HTTPS 地址，已自动转换 {} -> {}", url, ssh);
      return ssh;
    }
    if (RepositoryCatalogService.AUTH_HTTPS.equals(authType) && (url.startsWith("git@") || url.startsWith("ssh://"))) {
      throw new BusinessException(30022,
          "代码库认证为用户名+Token，但地址是 SSH 格式，请改为 https:// 地址：" + safeName(catalog));
    }
    return url;
  }

  /**
   * 判断 URL 是否为 HTTP/HTTPS 协议。
   *
   * @param url 待检测 URL
   * @return {@code true} 表示以 {@code http://} 或 {@code https://} 开头
   */
  private boolean isHttpUrl(String url) {
    String lower = url.toLowerCase(Locale.ROOT);
    return lower.startsWith("http://") || lower.startsWith("https://");
  }

  /**
   * 将 HTTPS 克隆地址转换为 SSH 格式。
   *
   * 示例：{@code https://github.com/owner/repo.git} → {@code git@github.com:owner/repo.git}
   *
   * @param httpsUrl HTTPS 仓库地址
   * @return SSH 格式地址
   * @throws BusinessException 无法解析 host/path（30022）
   */
  private String httpsToSshUrl(String httpsUrl) {
    try {
      java.net.URI uri = java.net.URI.create(httpsUrl);
      String host = uri.getHost();
      String path = uri.getPath();
      if (blank(host) || blank(path) || "/".equals(path)) {
        throw new BusinessException(30022, "无法将 HTTPS 地址转换为 SSH：" + httpsUrl);
      }
      path = path.startsWith("/") ? path.substring(1) : path;
      if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
      if (!path.endsWith(".git")) path = path + ".git";
      return "git@" + host + ":" + path;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(30022, "无法将 HTTPS 地址转换为 SSH：" + httpsUrl);
    }
  }

  /**
   * 克隆单个 Git 仓库到目标目录。
   *
   * SSH 认证走 {@link #cloneWithSystemGitSsh}（系统 git + 临时私钥 + {@code GIT_SSH_COMMAND}）；
   * 其余走 JGit {@link CloneCommand} 并由 {@link #applyHttpsOrFallbackAuth} 注入 HTTPS 凭据。
   *
   * @param url     克隆地址
   * @param branch  分支名，可为空
   * @param target  目标目录
   * @param catalog 代码库凭据
   * @throws Exception clone 失败
   */
  private void cloneRepository(String url, String branch, Path target, RepositoryCatalog catalog) throws Exception {
    String authType = catalog == null || blank(catalog.getAuthType())
        ? "" : catalog.getAuthType().trim().toUpperCase(Locale.ROOT);
    // SSH：走系统 git + OpenSSH（与本机 ssh -T 同一套），避免 JSch 对 ed25519 认证不稳定
    if (RepositoryCatalogService.AUTH_SSH.equals(authType)) {
      cloneWithSystemGitSsh(url, branch, target, catalog);
      return;
    }
    CloneCommand command = Git.cloneRepository().setURI(url).setDirectory(target.toFile());
    if (!blank(branch)) command.setBranch(branch);
    applyHttpsOrFallbackAuth(command, url, catalog);
    log.info("Git clone 开始(JGit) url={}, authType={}", url, blank(authType) ? "GLOBAL_FALLBACK" : authType);
    try (Git ignored = command.call()) {}
  }

  /**
   * 使用系统 git 并通过 OpenSSH 临时私钥执行浅克隆。
   *
   * 流程：{@link RepositoryCatalogService#decryptSecret} 解密私钥 → 写入临时文件
   *（{@code scan-center-ssh-*.key}）→ {@link #hardenKeyFilePermissions} →
   * 设置 {@code GIT_SSH_COMMAND} → {@code git clone --depth 1}。
   * 克隆结束后在 {@code finally} 中删除临时私钥文件。
   *
   * @param url     SSH 格式克隆地址（须非 HTTPS）
   * @param branch  分支名，可为空则使用默认分支
   * @param target  目标目录
   * @param catalog 代码库凭据
   * @throws BusinessException 缺少凭据、SSH 认证失败（30022）或 clone 失败（30021）
   * @throws Exception         进程 I/O 或等待失败
   */
  private void cloneWithSystemGitSsh(String url, String branch, Path target, RepositoryCatalog catalog) throws Exception {
    if (catalog == null) throw new BusinessException(30022, "SSH 拉取缺少代码库凭据");
    String secret = catalogService.decryptSecret(catalog);
    if (blank(secret)) throw new BusinessException(30022, "代码库未配置 SSH 私钥：" + safeName(catalog));
    if (isHttpUrl(url)) throw new BusinessException(30022, "SSH 地址无效：" + url);
    String privateKey = normalizePrivateKey(secret);
    Path keyFile = Files.createTempFile("scan-center-ssh-", ".key");
    Files.write(keyFile, privateKey.getBytes(StandardCharsets.UTF_8));
    try {
      hardenKeyFilePermissions(keyFile);
      log.info("SSH私钥已加载(system-git) catalog={}, keyChars={}, header={}",
          safeName(catalog), privateKey.length(), privateKeyHeader(privateKey));
      String keyPath = keyFile.toAbsolutePath().toString();
      String sshCommand = "ssh -i \"" + keyPath + "\" -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile="
          + (isWindows() ? "NUL" : "/dev/null");
      List<String> cmd = new ArrayList<String>();
      cmd.add("git");
      cmd.add("clone");
      cmd.add("--depth");
      cmd.add("1");
      if (!blank(branch)) {
        cmd.add("--branch");
        cmd.add(branch);
        cmd.add("--single-branch");
      }
      cmd.add(url);
      cmd.add(target.toAbsolutePath().toString());
      log.info("Git clone 开始(system-git) url={}, branch={}", url, blank(branch) ? "(default)" : branch);
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.environment().put("GIT_SSH_COMMAND", sshCommand);
      pb.redirectErrorStream(true);
      pb.directory(target.getParent() == null ? new File(".") : target.getParent().toFile());
      Process process = pb.start();
      String output = readProcessOutput(process);
      int code = process.waitFor();
      if (code != 0) {
        String lower = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (lower.contains("permission denied") || lower.contains("publickey") || lower.contains("auth fail")) {
          throw new BusinessException(30022,
              "SSH公钥认证失败。请确认：1) 代码库已重新保存与本机一致的私钥；2) 对应公钥已加到 GitHub；"
                  + "3) 本机先成功：ssh -i 私钥路径 -T git@github.com。详情：" + limit(output, 500));
        }
        throw new BusinessException(30021, "Git clone 失败(exit=" + code + ")：" + limit(output, 800));
      }
    } finally {
      try { Files.deleteIfExists(keyFile); } catch (Exception ignored) {}
    }
  }

  /**
   * 收紧临时 SSH 私钥文件权限，满足 OpenSSH 对私钥 ACL 的要求。
   *
   * Windows：{@code icacls} 移除继承并仅授予当前用户读权限；
   * Unix：设置为 {@code rw-------}。
   *
   * @param keyFile 临时私钥文件路径
   */
  private void hardenKeyFilePermissions(Path keyFile) {
    try {
      if (isWindows()) {
        String path = keyFile.toAbsolutePath().toString();
        String user = System.getProperty("user.name");
        // OpenSSH(Windows) 要求私钥 ACL 足够严格，否则可能直接拒绝使用
        new ProcessBuilder("icacls", path, "/inheritance:r").redirectErrorStream(true).start().waitFor();
        new ProcessBuilder("icacls", path, "/grant:r", user + ":(R)").redirectErrorStream(true).start().waitFor();
      } else {
        Set<java.nio.file.attribute.PosixFilePermission> perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(keyFile, perms);
      }
    } catch (Exception e) {
      log.warn("设置SSH私钥文件权限失败 path={}, err={}", keyFile, e.toString());
    }
  }

  /**
   * 读取子进程合并后的标准输出，最多约 4000 字符。
   *
   * @param process 已启动的子进程
   * @return 输出文本
   * @throws IOException 读取流失败
   */
  private String readProcessOutput(Process process) throws IOException {
    StringBuilder out = new StringBuilder();
    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    try {
      String line;
      while ((line = reader.readLine()) != null) {
        if (out.length() > 0) out.append('\n');
        out.append(line);
        if (out.length() > 4000) break;
      }
    } finally {
      reader.close();
    }
    return out.toString();
  }

  /**
   * 判断当前运行环境是否为 Windows。
   *
   * @return {@code true} 表示操作系统名称包含 {@code win}
   */
  private boolean isWindows() {
    String os = System.getProperty("os.name");
    return os != null && os.toLowerCase(Locale.ROOT).contains("win");
  }

  /**
   * 为 JGit {@link CloneCommand} 配置 HTTPS 或全局 fallback 认证。
   *
   * HTTPS 认证类型时调用 {@link RepositoryCatalogService#decryptSecret} 获取 Token，
   * 并通过 {@link UsernamePasswordCredentialsProvider} 注入。
   *
   * @param command JGit 克隆命令
   * @param url     仓库 URL
   * @param catalog 代码库凭据，可为 {@code null}
   * @throws BusinessException 凭据缺失或 SSH 仓库未配置私钥（30022）
   */
  private void applyHttpsOrFallbackAuth(CloneCommand command, String url, RepositoryCatalog catalog) {
    String authType = catalog == null || blank(catalog.getAuthType())
        ? null : catalog.getAuthType().trim().toUpperCase(Locale.ROOT);
    String secret = catalog == null ? "" : catalogService.decryptSecret(catalog);
    if (RepositoryCatalogService.AUTH_HTTPS.equals(authType)) {
      if (blank(secret)) throw new BusinessException(30022, "代码库未配置访问 Token：" + safeName(catalog));
      String user = blank(catalog.getGitUsername()) ? "token" : catalog.getGitUsername().trim();
      command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, secret));
      return;
    }
    if (!gitToken.isEmpty()) {
      command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(
          gitUsername.isEmpty() ? "token" : gitUsername, gitToken));
      return;
    }
    if (url.startsWith("git@") || url.startsWith("ssh://")) {
      throw new BusinessException(30022, "SSH 仓库未配置私钥，请在代码库中选择 SSH 认证并填写私钥");
    }
  }

  /**
   * 提取私钥 PEM 第一行（用于日志，避免泄露完整密钥）。
   *
   * @param key 规范化后的私钥文本
   * @return 首行或截断至 60 字符
   */
  private String privateKeyHeader(String key) {
    int end = key.indexOf('\n');
    String first = end < 0 ? key : key.substring(0, end);
    return first.length() > 60 ? first.substring(0, 60) : first;
  }

  /**
   * 安全获取代码库显示名称。
   *
   * @param catalog 代码库目录，可为 {@code null}
   * @return 仓库名或 {@code "未知"}
   */
  private String safeName(RepositoryCatalog catalog) {
    return catalog == null || blank(catalog.getRepositoryName()) ? "未知" : catalog.getRepositoryName();
  }

  /**
   * 规范化用户粘贴的 SSH 私钥文本。
   *
   * 统一换行、去掉行尾空格、去掉 Base64 行内空格，并校验 PEM 头尾；
   * 拒绝公钥或 SHA256 指纹误粘贴。
   *
   * @param raw 原始私钥字符串
   * @return 规范化后的 PEM 私钥（末尾带换行）
   * @throws BusinessException 格式不正确或误填公钥（30022）
   */
  private String normalizePrivateKey(String raw) {
    String key = raw == null ? "" : raw.trim();
    if (key.contains("\\n") && !key.contains("\n")) key = key.replace("\\n", "\n");
    key = key.replace("\r\n", "\n").replace('\r', '\n').trim();
    if (key.startsWith("SHA256:") || key.startsWith("ssh-ed25519 ") || key.startsWith("ssh-rsa ")) {
      throw new BusinessException(30022, "请粘贴私钥文件全文（id_ed25519），不要填公钥(.pub)或 SHA256 指纹");
    }
    if (!key.contains("BEGIN") || !key.contains("PRIVATE KEY") || !key.contains("END")) {
      throw new BusinessException(30022, "SSH私钥格式不正确：需包含 -----BEGIN ... PRIVATE KEY----- 与 END 行");
    }
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
      if (inBody) {
        // 终端复制常带行尾空格；Base64 行内空格也会破坏密钥
        cleaned.append(t.replace(" ", "").replace("\t", "")).append('\n');
      } else if (!t.isEmpty()) {
        cleaned.append(t).append('\n');
      }
    }
    key = cleaned.toString().trim();
    if (!key.endsWith("\n")) key = key + "\n";
    return key;
  }

  /**
   * 从旧版单项目字段构造 {@link CodeRepository.GitProject}（兼容无 JSON 项目列表的配置）。
   *
   * @param repository 扫描源配置
   * @return 合成的 Git 项目对象
   */
  private CodeRepository.GitProject legacyProject(CodeRepository repository) {
    CodeRepository.GitProject value = new CodeRepository.GitProject(); value.setId(repository.getRepositoryCatalogId());
    value.setName(repository.getRepositoryCode()); value.setUrl(repository.getRepositoryUrl()); value.setApplication(repository.getApplication()); return value;
  }

  /**
   * 将项目名转为安全的子目录名（仅保留字母数字及 {@code ._-}，最长 100 字符）。
   *
   * @param value 原始项目名
   * @return 安全目录名
   */
  private String safeDirectory(String value) {
    String safe = blank(value) ? "project" : value.replaceAll("[^A-Za-z0-9._\\-]", "_");
    return safe.length() <= 100 ? safe : safe.substring(0, 100);
  }

  /**
   * 检查目录树下是否至少存在一个普通文件。
   *
   * @param root 待检查根目录
   * @return {@code true} 表示存在至少一个普通文件
   * @throws IOException 遍历目录失败
   */
  private boolean containsRegularFile(Path root) throws IOException {
    try (Stream<Path> stream = Files.walk(root)) {
      return stream.anyMatch(Files::isRegularFile);
    }
  }

  /**
   * 删除指定快照的工作区目录（删除 {@code current} 的父级 snapshot 目录）。
   *
   * @param path 工作区路径（通常为 {@code .../current}），须在 {@link #snapshotRoot} 之下
   */
  public void deleteSnapshot(Path path) {
    if (path != null && path.normalize().startsWith(snapshotRoot)) safeDelete(path.getParent());
  }

  /**
   * 递归删除目录树（先删文件再删目录）。
   *
   * @param path 待删除根路径
   * @throws IOException 删除失败且无法 fallback 到 {@link File#delete()}
   */
  private void deleteTree(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (Stream<Path> stream = Files.walk(path)) {
      Iterator<Path> iterator = stream.sorted(Comparator.reverseOrder()).iterator();
      while (iterator.hasNext()) {
        Path next = iterator.next();
        try {
          Files.deleteIfExists(next);
        } catch (IOException e) {
          if (!next.toFile().delete() && Files.exists(next)) throw e;
        }
      }
    }
  }

  /**
   * 静默删除目录树，忽略所有异常。
   *
   * @param path 待删除路径
   */
  private void safeDelete(Path path) {
    try { deleteTree(path); } catch (Exception ignored) {}
  }

  /**
   * 截断字符串至指定最大长度。
   *
   * @param value 原字符串
   * @param size  最大长度
   * @return 截断后的字符串；{@code value} 为 {@code null} 时返回 {@code "未知错误"}
   */
  private String limit(String value, int size) {
    if (value == null) return "未知错误";
    return value.length() <= size ? value : value.substring(0, size);
  }

  /**
   * 判断字符串是否为 null 或空白。
   *
   * @param value 待检测字符串
   * @return {@code true} 表示 null 或 trim 后为空
   */
  private boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }

}
