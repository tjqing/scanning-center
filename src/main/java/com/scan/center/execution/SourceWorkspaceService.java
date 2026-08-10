package com.scan.center.execution;

import com.scan.center.exception.BusinessException;
import com.scan.center.model.CodeRepository;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SourceWorkspaceService {
  private final Path storageRoot;
  private final Path snapshotRoot;
  private final String gitUsername;
  private final String gitToken;
  private final MdDocumentHttpClient mdClient;

  public SourceWorkspaceService(
      @Value("${scan-center.storage-root}") String storageRoot,
      @Value("${scan-center.workspace-root}") String workspaceRoot,
      @Value("${scan-center.git.username:}") String gitUsername,
      @Value("${scan-center.git.token:}") String gitToken,
      MdDocumentHttpClient mdClient) {
    this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    this.snapshotRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize().resolve("snapshots");
    this.gitUsername = gitUsername == null ? "" : gitUsername;
    this.gitToken = gitToken == null ? "" : gitToken;
    this.mdClient = mdClient;
  }

  public Path prepare(Long snapshotId, CodeRepository repository, String versionNo) {
    Path base = snapshotRoot.resolve(String.valueOf(snapshotId)).normalize();
    Path backup = base.resolve("backup-" + UUID.randomUUID()).normalize();
    Path current = base.resolve("current").normalize();
    if (!base.startsWith(snapshotRoot)) throw new BusinessException(30020, "任务快照目录越界");
    try {
      Files.createDirectories(backup);
      if ("MD".equals(repository.getScanSourceType())) prepareMd(repository, versionNo, backup);
      else prepareCode(repository, backup);
      if (!containsRegularFile(backup)) throw new IOException("扫描源中没有可扫描文件");
      if (Files.exists(current)) deleteTree(current);
      Files.move(backup, current, StandardCopyOption.ATOMIC_MOVE);
      return current;
    } catch (BusinessException e) {
      safeDelete(backup);
      throw e;
    } catch (Exception e) {
      safeDelete(backup);
      throw new BusinessException(30021, "扫描源准备失败：" + limit(e.getMessage(), 1000));
    }
  }

  private void prepareCode(CodeRepository repository, Path target) throws Exception {
    if ("GIT".equals(repository.getSourceType())) {
      cloneRepository(repository, target);
    } else if ("UPLOAD".equals(repository.getSourceType())) {
      if (blank(repository.getStorageKey())) throw new IOException("ZIP扫描源尚未上传文件");
      Path zip = storageRoot.resolve(repository.getStorageKey()).normalize();
      if (!zip.startsWith(storageRoot) || !Files.isRegularFile(zip)) throw new IOException("ZIP源文件不存在");
      ZipExtractor.extract(zip, target);
    } else {
      throw new IOException("CODE扫描源只支持Git或ZIP：" + repository.getSourceType());
    }
  }

  private void prepareMd(CodeRepository repository, String versionNo, Path target) throws Exception {
    if (!"HTTP".equals(repository.getSourceType())) throw new IOException("MD扫描源必须使用HTTP获取方式");
    if (blank(repository.getApplication())) throw new IOException("MD扫描源未配置所属应用");
    if (blank(versionNo) || !versionNo.matches("\\d{6}")) throw new IOException("MD扫描版本必须为YYYYMM格式");
    mdClient.download(repository.getApplication(), versionNo, target);
  }

  private void cloneRepository(CodeRepository repository, Path target) throws Exception {
    if (blank(repository.getRepositoryUrl())) throw new IOException("Git仓库地址为空");
    CloneCommand command = Git.cloneRepository().setURI(repository.getRepositoryUrl()).setDirectory(target.toFile());
    if (!blank(repository.getDefaultBranch())) command.setBranch(repository.getDefaultBranch());
    if (!gitToken.isEmpty())
      command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(gitUsername.isEmpty() ? "token" : gitUsername, gitToken));
    try (Git ignored = command.call()) {}
  }

  private boolean containsRegularFile(Path root) throws IOException {
    try (Stream<Path> stream = Files.walk(root)) {
      return stream.anyMatch(Files::isRegularFile);
    }
  }

  public void deleteSnapshot(Path path) {
    if (path != null && path.normalize().startsWith(snapshotRoot)) safeDelete(path.getParent());
  }

  private void deleteTree(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (Stream<Path> stream = Files.walk(path)) {
      Iterator<Path> iterator = stream.sorted(Comparator.reverseOrder()).iterator();
      while (iterator.hasNext()) Files.deleteIfExists(iterator.next());
    }
  }

  private void safeDelete(Path path) {
    try { deleteTree(path); } catch (Exception ignored) {}
  }

  private String limit(String value, int size) {
    if (value == null) return "未知错误";
    return value.length() <= size ? value : value.substring(0, size);
  }

  private boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }

}
