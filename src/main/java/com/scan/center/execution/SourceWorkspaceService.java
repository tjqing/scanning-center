package com.scan.center.execution;

import com.scan.center.exception.BusinessException;
import com.scan.center.model.CodeRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  private final DataSource dataSource;

  public SourceWorkspaceService(
      @Value("${scan-center.storage-root}") String storageRoot,
      @Value("${scan-center.workspace-root}") String workspaceRoot,
      @Value("${scan-center.git.username:}") String gitUsername,
      @Value("${scan-center.git.token:}") String gitToken,
      DataSource dataSource) {
    this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    this.snapshotRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize().resolve("snapshots");
    this.gitUsername = gitUsername == null ? "" : gitUsername;
    this.gitToken = gitToken == null ? "" : gitToken;
    this.dataSource = dataSource;
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
    if (!"DATABASE".equals(repository.getSourceType())) throw new IOException("MD扫描源必须使用数据库获取方式");
    if (blank(repository.getApplication())) throw new IOException("MD扫描源未配置所属应用");
    if (blank(versionNo) || !versionNo.matches("\\d{6}")) throw new IOException("MD扫描版本必须为YYYYMM格式");
    fetchDatabaseDocuments(repository, repository.getApplication(), versionNo, target);
  }

  private void cloneRepository(CodeRepository repository, Path target) throws Exception {
    if (blank(repository.getRepositoryUrl())) throw new IOException("Git仓库地址为空");
    CloneCommand command = Git.cloneRepository().setURI(repository.getRepositoryUrl()).setDirectory(target.toFile());
    if (!blank(repository.getDefaultBranch())) command.setBranch(repository.getDefaultBranch());
    if (!gitToken.isEmpty())
      command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(gitUsername.isEmpty() ? "token" : gitUsername, gitToken));
    try (Git ignored = command.call()) {}
  }

  private void fetchDatabaseDocuments(
      CodeRepository repository, String application, String versionNo, Path target) throws Exception {
    ParameterizedQuery query = parameterize(repository.getDocumentQuery(), application, versionNo);
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(query.sql)) {
      connection.setReadOnly(true); statement.setMaxRows(10000); statement.setQueryTimeout(60);
      for (int i = 0; i < query.parameters.size(); i++) statement.setString(i + 1, query.parameters.get(i));
      try (ResultSet rows = statement.executeQuery()) {
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

  private ParameterizedQuery parameterize(String sql, String application, String versionNo) throws IOException {
    if (blank(sql)) throw new IOException("MD文档查询SQL为空");
    Matcher matcher = Pattern.compile(":(application|version)\\b").matcher(sql);
    StringBuffer prepared = new StringBuffer();
    List<String> values = new ArrayList<String>();
    boolean hasApplication = false, hasVersion = false;
    while (matcher.find()) {
      String name = matcher.group(1);
      if ("application".equals(name)) { values.add(application); hasApplication = true; }
      else { values.add(versionNo); hasVersion = true; }
      matcher.appendReplacement(prepared, "?");
    }
    matcher.appendTail(prepared);
    if (!hasApplication || !hasVersion) throw new IOException("MD文档查询必须包含:application和:version参数");
    return new ParameterizedQuery(prepared.toString(), values);
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

  private static class ParameterizedQuery {
    private final String sql;
    private final List<String> parameters;
    private ParameterizedQuery(String sql, List<String> parameters) {
      this.sql = sql;
      this.parameters = parameters;
    }
  }
}
