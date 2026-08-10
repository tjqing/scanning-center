package com.scan.center.execution;

import com.scan.center.exception.BusinessException;
import com.scan.center.mapper.RepositoryMapper;
import com.scan.center.model.CodeRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
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
  private final RepositoryMapper repositoryMapper;
  private final DataSource dataSource;

  public SourceWorkspaceService(
      @Value("${scan-center.storage-root}") String storageRoot,
      @Value("${scan-center.workspace-root}") String workspaceRoot,
      @Value("${scan-center.git.username:}") String gitUsername,
      @Value("${scan-center.git.token:}") String gitToken,
      RepositoryMapper repositoryMapper,
      DataSource dataSource) {
    this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    this.snapshotRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize().resolve("snapshots");
    this.gitUsername = gitUsername == null ? "" : gitUsername;
    this.gitToken = gitToken == null ? "" : gitToken;
    this.repositoryMapper = repositoryMapper;
    this.dataSource = dataSource;
  }

  public Path prepare(Long snapshotId, CodeRepository repository, String taskType, String versionNo) {
    Path base = snapshotRoot.resolve(String.valueOf(snapshotId)).normalize();
    Path backup = base.resolve("backup-" + UUID.randomUUID()).normalize();
    Path current = base.resolve("current").normalize();
    if (!base.startsWith(snapshotRoot)) throw new BusinessException(30020, "任务快照目录越界");
    try {
      Files.createDirectories(backup);
      if ("MD".equals(taskType)) prepareMd(repository, versionNo, backup, base);
      else prepareSingle(repository, backup);
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

  private void prepareSingle(CodeRepository repository, Path target) throws Exception {
    if ("GIT".equals(repository.getSourceType())) {
      cloneRepository(repository, target);
    } else if ("UPLOAD".equals(repository.getSourceType())) {
      if (blank(repository.getStorageKey())) throw new IOException("ZIP扫描源尚未上传文件");
      Path zip = storageRoot.resolve(repository.getStorageKey()).normalize();
      if (!zip.startsWith(storageRoot) || !Files.isRegularFile(zip)) throw new IOException("ZIP源文件不存在");
      ZipExtractor.extract(zip, target);
    } else if ("DATABASE".equals(repository.getSourceType())) {
      fetchDatabaseDocuments(repository, target);
    } else {
      throw new IOException("不支持的扫描源类型：" + repository.getSourceType());
    }
  }

  private void prepareMd(CodeRepository selected, String versionNo, Path target, Path base) throws Exception {
    if (blank(selected.getApplication())) throw new IOException("代码库未配置所属应用");
    if (blank(versionNo) || !versionNo.matches("\\d{6}")) throw new IOException("MD扫描版本必须为YYYYMM格式");
    List<CodeRepository> repositories = repositoryMapper.findByApplication(selected.getApplication());
    if (repositories.isEmpty()) throw new IOException("应用下没有启用的Git代码库");
    Path clones = base.resolve("md-clones-" + UUID.randomUUID()).normalize();
    Files.createDirectories(clones);
    int copied = 0;
    try {
      for (CodeRepository repository : repositories) {
        if (blank(repository.getDesignDocumentPath())) continue;
        String code = blank(repository.getRepositoryCode()) ? "repository-" + repository.getId() : repository.getRepositoryCode();
        Path clone = clones.resolve(code).normalize();
        cloneRepository(repository, clone);
        Path docs = clone.resolve(repository.getDesignDocumentPath()).resolve(versionNo).normalize();
        if (!docs.startsWith(clone) || !Files.isDirectory(docs)) continue;
        Path repositoryTarget = target.resolve(code).normalize();
        copied += copyTree(docs, repositoryTarget);
      }
    } finally {
      safeDelete(clones);
    }
    if (copied == 0) throw new IOException("应用代码库中不存在指定版本的设计文档");
  }

  private void cloneRepository(CodeRepository repository, Path target) throws Exception {
    if (blank(repository.getRepositoryUrl())) throw new IOException("Git仓库地址为空");
    CloneCommand command = Git.cloneRepository().setURI(repository.getRepositoryUrl()).setDirectory(target.toFile());
    if (!blank(repository.getDefaultBranch())) command.setBranch(repository.getDefaultBranch());
    if (!gitToken.isEmpty())
      command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(gitUsername.isEmpty() ? "token" : gitUsername, gitToken));
    try (Git ignored = command.call()) {}
  }

  private void fetchDatabaseDocuments(CodeRepository repository, Path target) throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      connection.setReadOnly(true); statement.setMaxRows(10000); statement.setQueryTimeout(60);
      try (ResultSet rows = statement.executeQuery(repository.getDocumentQuery())) {
        int index = 0;
        while (rows.next()) {
          index++;
          String name = rows.getString(repository.getDocumentNameColumn());
          String type = blank(repository.getDocumentTypeColumn()) ? "txt" : rows.getString(repository.getDocumentTypeColumn());
          String safe = safeFileName(blank(name) ? "document-" + index : name);
          if (!safe.contains(".")) safe += "." + safeFileName(type == null ? "txt" : type).toLowerCase(Locale.ROOT);
          Path file = target.resolve(String.format("%05d-%s", index, safe)).normalize();
          if (!file.startsWith(target)) throw new IOException("非法文档名称");
          Object content = rows.getObject(repository.getDocumentContentColumn());
          if (content instanceof byte[]) Files.write(file, (byte[]) content);
          else if (content instanceof Blob) {
            try (InputStream input = ((Blob) content).getBinaryStream()) {
              Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
            }
          } else Files.write(file, String.valueOf(content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        }
      }
    }
  }

  private int copyTree(Path source, Path target) throws IOException {
    int[] count = {0};
    try (Stream<Path> stream = Files.walk(source)) {
      stream.forEach(path -> {
        try {
          Path relative = source.relativize(path);
          Path destination = target.resolve(relative).normalize();
          if (!destination.startsWith(target)) throw new IOException("文档路径越界");
          if (Files.isDirectory(path)) Files.createDirectories(destination);
          else if (Files.isRegularFile(path)) {
            Files.createDirectories(destination.getParent());
            Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            count[0]++;
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
    return count[0];
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

  private String safeFileName(String value) {
    return Paths.get(value).getFileName().toString().replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
  }

  private String limit(String value, int size) {
    if (value == null) return "未知错误";
    return value.length() <= size ? value : value.substring(0, size);
  }

  private boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
