package com.icbc.scan.center.execution;

import com.icbc.scan.center.common.AuditOperator;
import com.icbc.scan.center.mapper.*;
import com.icbc.scan.center.model.*;
import com.icbc.scan.center.service.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;
import java.util.zip.*;
import javax.sql.DataSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ScanTaskExecutor {
  private static final Logger log = LoggerFactory.getLogger(ScanTaskExecutor.class);
  private final TaskMapper taskMapper;
  private final ResultMapper resultMapper;
  private final RepositoryService repositoryService;
  private final Path storageRoot;
  private final Path workspaceRoot;
  private final DataSource dataSource;

  public ScanTaskExecutor(
      TaskMapper taskMapper,
      ResultMapper resultMapper,
      RepositoryService repositoryService,
      DataSource dataSource,
      @Value("${scan-center.storage-root}") String storage,
      @Value("${scan-center.workspace-root}") String workspace) {
    this.taskMapper = taskMapper;
    this.resultMapper = resultMapper;
    this.repositoryService = repositoryService;
    this.dataSource = dataSource;
    this.storageRoot = Paths.get(storage).toAbsolutePath().normalize();
    this.workspaceRoot = Paths.get(workspace).toAbsolutePath().normalize();
  }

  @Async
  @EventListener
  public void onQueued(TaskService.TaskQueuedEvent event) {
    execute(event.getTaskId());
  }

  public void execute(Long taskId) {
    if (taskMapper.start(taskId) != 1) return;
    Path work = workspaceRoot.resolve(String.valueOf(taskId)).normalize();
    String finalStatus = "FAILED", error = null;
    try {
      clean(work);
      Files.createDirectories(work);
      ScanTask task = taskMapper.findById(taskId);
      CodeRepository repo = repositoryService.getInternal(task.getRepositoryId());
      prepare(repo, work);
      List<Path> files = discover(work, repo);
      taskMapper.updateTotal(taskId, files.size());
      resultMapper.deleteIssuesByTask(taskId);
      resultMapper.deleteResultByTask(taskId);
      ScanResult result = new ScanResult();
      result.setTaskId(taskId);
      result.setOperatorUserId(AuditOperator.USER_ID);
      result.setOperatorUserName(AuditOperator.USER_NAME);
      resultMapper.insertResult(result);
      List<ScanRule> rules = taskMapper.findTaskRules(taskId);
      for (Path file : files) {
        ScanTask current = taskMapper.findById(taskId);
        if (Boolean.TRUE.equals(current.getCancelRequested())) {
          finalStatus = "CANCELLED";
          break;
        }
        String relative = work.relativize(file).toString().replace('\\', '/');
        taskMapper.insertFile(taskId, relative);
        try {
          String content = parse(file);
          int count = scan(task, repo, result, rules, relative, content);
          taskMapper.finishFile(taskId, relative, "SUCCESS", count, null);
          taskMapper.updateProgress(taskId, true, count);
        } catch (Exception e) {
          String m = limit(e.getMessage(), 1000);
          taskMapper.finishFile(taskId, relative, "FAILED", 0, m);
          taskMapper.updateProgress(taskId, false, 0);
          log.warn("scan file failed taskId={}, path={}", taskId, relative, e);
        }
      }
      ScanTask end = taskMapper.findById(taskId);
      if (!"CANCELLED".equals(finalStatus)) {
        if (end.getSuccessFiles() == 0 && end.getFailedFiles() > 0) finalStatus = "FAILED";
        else if (end.getFailedFiles() > 0) finalStatus = "PARTIAL_SUCCESS";
        else finalStatus = "SUCCESS";
      }
      resultMapper.refreshSummary(taskId);
    } catch (Exception e) {
      error = limit(e.getMessage(), 1800);
      log.error("scan task failed taskId={}", taskId, e);
    } finally {
      taskMapper.finish(taskId, finalStatus, error);
      try {
        clean(work);
      } catch (Exception e) {
        log.warn("cleanup failed taskId={}", taskId, e);
      }
    }
  }

  private void prepare(CodeRepository repo, Path work) throws Exception {
    if ("GIT".equals(repo.getSourceType())) {
      CloneCommand c =
          Git.cloneRepository().setURI(repo.getRepositoryUrl()).setDirectory(work.toFile());
      if (repo.getDefaultBranch() != null && !repo.getDefaultBranch().isEmpty())
        c.setBranch(repo.getDefaultBranch());
      if (repo.getEncryptedToken() != null && !repo.getEncryptedToken().isEmpty())
        c.setCredentialsProvider(
            new UsernamePasswordCredentialsProvider(
                repo.getUsername() == null ? "token" : repo.getUsername(),
                repo.getEncryptedToken()));
      try (Git ignored = c.call()) {}
      return;
    }
    if ("DATABASE".equals(repo.getSourceType())) {
      fetchDatabaseDocuments(repo, work);
      return;
    }
    if (repo.getStorageKey() == null) throw new IllegalStateException("资源库尚未上传文件");
    Path source = storageRoot.resolve(repo.getStorageKey()).normalize();
    if (!source.startsWith(storageRoot) || !Files.exists(source))
      throw new IllegalStateException("源文件不存在");
    String original = repo.getOriginalFileName() == null ? "upload" : repo.getOriginalFileName();
    if (!original.toLowerCase().endsWith(".zip")) {
      throw new IllegalStateException("上传类资源必须为ZIP格式");
    }
    ZipExtractor.extract(source, work);
  }

  private void fetchDatabaseDocuments(CodeRepository repo, Path work) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      try (Statement statement = connection.createStatement()) {
        statement.setMaxRows(10000);
        statement.setQueryTimeout(60);
        try (ResultSet rows = statement.executeQuery(repo.getDocumentQuery())) {
          int index = 0;
          while (rows.next()) {
            index++;
            String name = rows.getString(repo.getDocumentNameColumn());
            String type = blank(repo.getDocumentTypeColumn()) ? null : rows.getString(repo.getDocumentTypeColumn());
            name = documentFileName(name, type, index);
            Path target = work.resolve(String.format("%05d-%s", index, name)).normalize();
            if (!target.startsWith(work)) throw new IOException("非法文档名称");
            Object content = rows.getObject(repo.getDocumentContentColumn());
            if (content instanceof byte[]) Files.write(target, (byte[]) content);
            else if (content instanceof Blob) {
              Blob blob = (Blob) content;
              try (InputStream input = blob.getBinaryStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
              }
            } else {
              Files.write(target, String.valueOf(content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            }
          }
        }
      }
    }
  }

  private String documentFileName(String name, String type, int index) {
    String value = blank(name) ? "document-" + index : safeFileName(name);
    value = value.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
    if (!value.contains(".")) {
      String extension = blank(type) ? "txt" : type.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
      value += "." + (extension.isEmpty() ? "txt" : extension);
    }
    return value;
  }

  private boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private List<Path> discover(Path root, CodeRepository repo) throws Exception {
    List<Path> out = new ArrayList<Path>();
    final Set<String> exts = split(repo.getFileTypes());
    final List<String> excludes = new ArrayList<String>(split(repo.getExcludePatterns()));
    excludes.add(".git");
    excludes.add("node_modules");
    excludes.add("target");
    try (Stream<Path> s = Files.walk(root)) {
      s.filter(Files::isRegularFile)
          .forEach(
              p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String ext = extension(rel);
                boolean excluded = false;
                for (String x : excludes)
                  if (!x.isEmpty() && rel.contains(x)) {
                    excluded = true;
                    break;
                  }
                if (!excluded && (exts.isEmpty() || exts.contains(ext)) && isSupported(ext))
                  out.add(p);
              });
    }
    return out;
  }

  private boolean isSupported(String e) {
    return Arrays.asList(
            "java",
            "js",
            "vue",
            "xml",
            "yml",
            "yaml",
            "json",
            "md",
            "txt",
            "sql",
            "properties",
            "html",
            "css",
            "docx",
            "pdf")
        .contains(e);
  }

  private String parse(Path p) throws Exception {
    String e = extension(p.getFileName().toString());
    if ("docx".equals(e)) {
      StringBuilder b = new StringBuilder();
      try (XWPFDocument d = new XWPFDocument(Files.newInputStream(p))) {
        for (XWPFParagraph x : d.getParagraphs()) b.append(x.getText()).append('\n');
      }
      return b.toString();
    }
    if ("pdf".equals(e)) {
      try (PDDocument d = PDDocument.load(p.toFile())) {
        return new PDFTextStripper().getText(d);
      }
    }
    long size = Files.size(p);
    if (size > 10L * 1024 * 1024) throw new IOException("单文件大小超过10MB");
    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
  }

  private int scan(
      ScanTask task,
      CodeRepository repo,
      ScanResult result,
      List<ScanRule> rules,
      String path,
      String content) {
    int count = 0;
    String[] lines = content.split("\\r?\\n", -1);
    for (ScanRule r : rules) {
      if (!applies(r, path) || "AI".equals(r.getRuleType())) continue;
      Pattern pattern;
      if ("REGEX".equals(r.getMatchType()))
        pattern =
            Pattern.compile(
                r.getMatchContent(),
                Boolean.TRUE.equals(r.getCaseSensitive()) ? 0 : Pattern.CASE_INSENSITIVE);
      else
        pattern =
            Pattern.compile(
                Pattern.quote(r.getMatchContent()),
                Boolean.TRUE.equals(r.getCaseSensitive()) ? 0 : Pattern.CASE_INSENSITIVE);
      for (int i = 0; i < lines.length; i++) {
        Matcher m = pattern.matcher(lines[i]);
        while (m.find()) {
          ScanIssue issue = new ScanIssue();
          issue.setResultId(result.getId());
          issue.setTaskId(task.getId());
          issue.setRepositoryId(repo.getId());
          issue.setRuleId(r.getId());
          issue.setTitle(r.getRuleName());
          issue.setRiskLevel(r.getRiskLevel());
          issue.setRuleType(r.getRuleType());
          issue.setFilePath(path);
          issue.setStartLine(i + 1);
          issue.setEndLine(i + 1);
          issue.setMatchedContent(limit(m.group(), 500));
          issue.setContextContent(limit(lines[i], 1500));
          issue.setIssueDescription(r.getIssueDescription());
          issue.setSuggestion(r.getSuggestion());
          issue.setOperatorUserId(AuditOperator.USER_ID);
          issue.setOperatorUserName(AuditOperator.USER_NAME);
          resultMapper.insertIssue(issue);
          count++;
          if (count >= 10000) return count;
        }
      }
    }
    return count;
  }

  private boolean applies(ScanRule r, String path) {
    Set<String> types = split(r.getFileTypes());
    if (!types.isEmpty() && !types.contains(extension(path))) return false;
    for (String x : split(r.getExcludePatterns())) if (path.contains(x)) return false;
    return true;
  }

  private Set<String> split(String s) {
    Set<String> out = new HashSet<String>();
    if (s != null)
      for (String x : s.split("[,;\\s]+"))
        if (!x.trim().isEmpty()) out.add(x.trim().toLowerCase().replace(".", ""));
    return out;
  }

  private String extension(String n) {
    int i = n.lastIndexOf('.');
    return i < 0 ? "" : n.substring(i + 1).toLowerCase();
  }

  private String safeFileName(String n) {
    return Paths.get(n).getFileName().toString();
  }

  private String limit(String s, int n) {
    if (s == null) return null;
    return s.length() > n ? s.substring(0, n) : s;
  }

  private void clean(Path root) throws Exception {
    if (!Files.exists(root)) return;
    List<Path> paths = new ArrayList<Path>();
    try (Stream<Path> s = Files.walk(root)) {
      s.forEach(paths::add);
    }
    Collections.sort(paths, Collections.reverseOrder());
    for (Path p : paths) if (p.startsWith(workspaceRoot)) Files.deleteIfExists(p);
  }
}
