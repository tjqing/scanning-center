package com.scan.center.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan.center.common.*;
import com.scan.center.dto.TaskCreateDTO;
import com.scan.center.exception.BusinessException;
import com.scan.center.mapper.*;
import com.scan.center.model.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {
  private final TaskMapper mapper;
  private final RuleMapper ruleMapper;
  private final RepositoryService repositoryService;
  private final ObjectMapper json;
  private final ApplicationEventPublisher publisher;

  public TaskService(
      TaskMapper mapper,
      RuleMapper ruleMapper,
      RepositoryService repositoryService,
      ObjectMapper json,
      ApplicationEventPublisher publisher) {
    this.mapper = mapper;
    this.ruleMapper = ruleMapper;
    this.repositoryService = repositoryService;
    this.json = json;
    this.publisher = publisher;
  }

  public PageResult<ScanTask> page(String keyword, String status, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    return new PageResult<ScanTask>(
        mapper.page(keyword, status, (p - 1) * s, s), p, s, mapper.count(keyword, status));
  }

  public ScanTask get(Long id) {
    ScanTask v = mapper.findById(id);
    if (v == null) throw new BusinessException(30001, "扫描任务不存在");
    return v;
  }

  @Transactional(rollbackFor = Exception.class)
  public Long create(TaskCreateDTO dto) {
    CodeRepository repo = repositoryService.getInternal(dto.getRepositoryId());
    if (!Boolean.TRUE.equals(repo.getEnabled())) throw new BusinessException(30002, "资源库已停用");
    if ("UPLOAD".equals(repo.getSourceType())
        && (repo.getStorageKey() == null || repo.getStorageKey().trim().isEmpty())) {
      throw new BusinessException(30008, "ZIP资源库尚未上传文件，不能创建扫描任务");
    }
    List<ScanRule> rules = ruleMapper.findByIds(dto.getRuleIds());
    if (rules.size() != new HashSet<Long>(dto.getRuleIds()).size())
      throw new BusinessException(30003, "存在无效或停用规则");
    ScanTask t = new ScanTask();
    t.setTaskNo("SCAN" + System.currentTimeMillis());
    t.setTaskName(dto.getTaskName());
    t.setDescription(dto.getDescription());
    t.setRepositoryId(dto.getRepositoryId());
    t.setScopeJson(dto.getScopeJson());
    t.setStatus("PENDING");
    t.setOperatorUserId(AuditOperator.USER_ID);
    t.setOperatorUserName(AuditOperator.USER_NAME);
    mapper.insert(t);
    try {
      for (ScanRule r : rules)
        mapper.insertTaskRule(t.getId(), r.getId(), json.writeValueAsString(r));
    } catch (Exception e) {
      throw new BusinessException(30004, "规则快照生成失败");
    }
    if (Boolean.TRUE.equals(dto.getExecuteImmediately())) {
      mapper.queue(t.getId());
      publisher.publishEvent(new TaskQueuedEvent(t.getId()));
    }
    return t.getId();
  }

  @Transactional(rollbackFor = Exception.class)
  public void execute(Long id) {
    ScanTask t = get(id);
    if (mapper.queue(id) != 1)
      throw new BusinessException(30005, "只有待执行任务可以执行，已执行过的任务不能重复执行");
    publisher.publishEvent(new TaskQueuedEvent(id));
  }

  @Transactional(rollbackFor = Exception.class)
  public void cancel(Long id) {
    get(id);
    if (mapper.requestCancel(id) != 1) throw new BusinessException(30006, "当前任务状态不允许取消");
  }

  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    get(id);
    if (mapper.deletePending(id) != 1) throw new BusinessException(30007, "只能删除待执行任务");
  }

  public static class TaskQueuedEvent {
    private final Long taskId;

    public TaskQueuedEvent(Long id) {
      this.taskId = id;
    }

    public Long getTaskId() {
      return taskId;
    }
  }
}
