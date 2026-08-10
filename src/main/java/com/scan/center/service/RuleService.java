package com.scan.center.service;

import com.scan.center.common.*;
import com.scan.center.dto.RuleSaveDTO;
import com.scan.center.exception.BusinessException;
import com.scan.center.mapper.RuleMapper;
import com.scan.center.model.ScanRule;
import java.util.regex.Pattern;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleService {
  private final RuleMapper mapper;

  public RuleService(RuleMapper mapper) {
    this.mapper = mapper;
  }

  public PageResult<ScanRule> page(
      String keyword, String type, Boolean enabled, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    return new PageResult<ScanRule>(
        mapper.page(keyword, type, enabled, (p - 1) * s, s),
        p,
        s,
        mapper.count(keyword, type, enabled));
  }

  public ScanRule get(Long id) {
    ScanRule v = mapper.findById(id);
    if (v == null) throw new BusinessException(10001, "规则不存在");
    return v;
  }

  @Transactional(rollbackFor = Exception.class)
  public Long create(RuleSaveDTO dto) {
    validate(dto);
    ScanRule v = new ScanRule();
    BeanUtils.copyProperties(dto, v);
    v.setOperatorUserId(AuditOperator.USER_ID);
    v.setOperatorUserName(AuditOperator.USER_NAME);
    v.setOwnerUserId(AuditOperator.USER_ID);
    v.setOwnerUserName(AuditOperator.USER_NAME);
    v.setVisibility(dto.getVisibility() == null ? "PRIVATE" : dto.getVisibility());
    v.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
    mapper.insert(v);
    return v.getId();
  }

  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, RuleSaveDTO dto) {
    get(id);
    validate(dto);
    ScanRule v = new ScanRule();
    BeanUtils.copyProperties(dto, v);
    v.setOperatorUserId(AuditOperator.USER_ID);
    v.setOperatorUserName(AuditOperator.USER_NAME);
    v.setOwnerUserId(AuditOperator.USER_ID);
    v.setOwnerUserName(AuditOperator.USER_NAME);
    v.setVisibility(dto.getVisibility() == null ? "PRIVATE" : dto.getVisibility());
    v.setId(id);
    v.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
    mapper.update(v);
  }

  @Transactional(rollbackFor = Exception.class)
  public void status(Long id, boolean enabled) {
    get(id);
    mapper.updateStatus(id, enabled);
  }

  @Transactional(rollbackFor = Exception.class)
  public Long copy(Long id) {
    ScanRule old = get(id);
    old.setId(null);
    old.setRuleCode(old.getRuleCode() + "_COPY_" + System.currentTimeMillis());
    old.setRuleName(old.getRuleName() + "-副本");
    old.setEnabled(false);
    mapper.insert(old);
    return old.getId();
  }

  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    get(id);
    if (mapper.countTaskReference(id) > 0) throw new BusinessException(10002, "规则已被任务引用，只能停用");
    mapper.logicalDelete(id);
  }

  private void validate(RuleSaveDTO dto) {
    if (!dto.getRuleCode().matches("[A-Za-z0-9_-]+"))
      throw new BusinessException(10006, "规则编码只能包含字母、数字、下划线和短横线");
    if (!java.util.Arrays.asList("NORMAL", "AI", "MD").contains(dto.getRuleType())
        || !java.util.Arrays.asList("ALL", "CODE", "DOCUMENT").contains(dto.getTargetType())
        || !java.util.Arrays.asList("HIGH", "MEDIUM", "LOW", "INFO").contains(dto.getRiskLevel()))
      throw new BusinessException(10007, "规则类型、扫描对象或风险等级不合法");
    validateCommaList(dto.getFileTypes(), "文件类型", "[A-Za-z0-9]+(?:[._+-][A-Za-z0-9]+)*");
    validateCommaList(dto.getExcludePatterns(), "排除目录", "[^,，;；\\s]+");
    if ("NORMAL".equals(dto.getRuleType())) {
      if (dto.getMatchType() == null || dto.getMatchContent() == null)
        throw new BusinessException(10003, "普通规则必须配置匹配方式和内容");
      if ("REGEX".equals(dto.getMatchType()))
        try {
          Pattern.compile(dto.getMatchContent());
        } catch (Exception e) {
          throw new BusinessException(10004, "正则表达式不合法");
        }
    } else if ("AI".equals(dto.getRuleType())) {
      if (blank(dto.getCheckRuleContent()) || blank(dto.getResultUpdateContent()))
        throw new BusinessException(10005, "AI规则必须分别配置检查规则和结果更新");
    } else if ("MD".equals(dto.getRuleType()) && blank(dto.getCheckRuleContent())) {
      throw new BusinessException(10005, "MD规则必须配置检查规则");
    }
    if (dto.getVisibility() != null && !java.util.Arrays.asList("PRIVATE", "SHARED").contains(dto.getVisibility()))
      throw new BusinessException(10009, "规则可见范围不合法");
  }

  private void validateCommaList(String value, String name, String tokenPattern) {
    if (value == null || value.trim().isEmpty()) return;
    if (value.contains("，") || value.contains(";") || value.contains("；")
        || value.startsWith(",") || value.endsWith(",") || value.contains(",,"))
      throw new BusinessException(10008, name + "必须使用英文逗号分隔，且不能有空项");
    for (String token : value.split(","))
      if (!token.trim().matches(tokenPattern))
        throw new BusinessException(10008, name + "中包含非法项：" + token.trim());
  }

  private boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
