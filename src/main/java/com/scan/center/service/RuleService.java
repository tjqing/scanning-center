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

/**
 * 扫描规则领域服务。
 *负责普通规则与 AI 规则的 CRUD、启停、复制及保存前校验。
 */
@Service
public class RuleService {

  /** 扫描规则数据访问层 */
  private final RuleMapper mapper;

  /**
   * @param mapper 规则 Mapper
   */
  public RuleService(RuleMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 分页查询扫描规则。
   *
   * @param keyword 关键词，匹配规则编码/名称
   * @param type    规则类型（NORMAL/AI），可为空
   * @param enabled 启用状态筛选，可为空
   * @param page    页码
   * @param size    每页条数，限制在 1~100
   * @return 规则分页结果
   */
  public PageResult<ScanRule> page(
      String keyword, String type, Boolean enabled, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    return new PageResult<ScanRule>(
        mapper.page(keyword, type, enabled, (p - 1) * s, s),
        p,
        s,
        mapper.count(keyword, type, enabled));
  }

  /**
   * 按主键获取规则。
   *
   * @param id 规则 ID
   * @return 规则实体
   * @throws BusinessException 规则不存在时抛出（10001）
   */
  public ScanRule get(Long id) {
    ScanRule v = mapper.findById(id);
    if (v == null) throw new BusinessException(10001, "规则不存在");
    return v;
  }

  /**
   * 新建扫描规则。
   *
   * @param dto 规则保存参数
   * @return 新规则 ID
   * @throws BusinessException 校验失败时抛出（10003~10009 等）
   */
  @Transactional(rollbackFor = Exception.class)
  public Long create(RuleSaveDTO dto) {
    validate(dto);
    ScanRule v = new ScanRule();
    BeanUtils.copyProperties(dto, v);
    v.setOperatorUserId(AuditOperator.userId());
    v.setOperatorUserName(AuditOperator.userName());
    v.setOwnerUserId(AuditOperator.userId());
    v.setOwnerUserName(AuditOperator.userName());
    v.setVisibility(dto.getVisibility() == null ? "PRIVATE" : dto.getVisibility());
    v.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
    mapper.insert(v);
    return v.getId();
  }

  /**
   * 更新已有规则。
   *
   * @param id  规则 ID
   * @param dto 规则保存参数
   * @throws BusinessException 规则不存在（10001）或校验失败
   */
  @Transactional(rollbackFor = Exception.class)
  public void update(Long id, RuleSaveDTO dto) {
    get(id);
    validate(dto);
    ScanRule v = new ScanRule();
    BeanUtils.copyProperties(dto, v);
    v.setOperatorUserId(AuditOperator.userId());
    v.setOperatorUserName(AuditOperator.userName());
    v.setOwnerUserId(AuditOperator.userId());
    v.setOwnerUserName(AuditOperator.userName());
    v.setVisibility(dto.getVisibility() == null ? "PRIVATE" : dto.getVisibility());
    v.setId(id);
    v.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
    mapper.update(v);
  }

  /**
   * 启用或停用规则。
   *
   * @param id      规则 ID
   * @param enabled 目标启用状态
   * @throws BusinessException 规则不存在时抛出（10001）
   */
  @Transactional(rollbackFor = Exception.class)
  public void status(Long id, boolean enabled) {
    get(id);
    mapper.updateStatus(id, enabled);
  }

  /**
   * 复制规则为副本（编码追加 _COPY_ 时间戳，默认停用）。
   *
   * @param id 源规则 ID
   * @return 新规则 ID
   * @throws BusinessException 源规则不存在时抛出（10001）
   */
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

  /**
   * 逻辑删除规则；已被任务引用时不允许删除。
   *
   * @param id 规则 ID
   * @throws BusinessException 规则不存在（10001）或已被任务引用（10002）
   */
  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    get(id);
    if (mapper.countTaskReference(id) > 0) throw new BusinessException(10002, "规则已被任务引用，只能停用");
    mapper.logicalDelete(id);
  }

  /**
   * 校验规则保存参数：编码格式、类型枚举、匹配内容、AI 配置及可见范围等。
   *
   * @param dto 待校验的保存参数
   * @throws BusinessException 任一校验项不合法时抛出
   */
  private void validate(RuleSaveDTO dto) {
    if (!dto.getRuleCode().matches("[A-Za-z0-9_-]+"))
      throw new BusinessException(10006, "规则编码只能包含字母、数字、下划线和短横线");
    if (!java.util.Arrays.asList("NORMAL", "AI").contains(dto.getRuleType())
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
    } else {
      if (blank(dto.getCheckRuleContent()) || blank(dto.getResultUpdateContent()))
        throw new BusinessException(10005, "AI规则必须分别配置检查规则和结果更新");
    }
    if (dto.getVisibility() != null && !java.util.Arrays.asList("PRIVATE", "SHARED").contains(dto.getVisibility()))
      throw new BusinessException(10009, "规则可见范围不合法");
  }

  /**
   * 校验英文逗号分隔的列表项，每项须匹配给定正则。
   *
   * @param value        逗号分隔字符串，空则跳过
   * @param name         字段中文名，用于错误提示
   * @param tokenPattern 单项合法格式正则
   * @throws BusinessException 分隔符或单项格式不合法时抛出（10008）
   */
  private void validateCommaList(String value, String name, String tokenPattern) {
    if (value == null || value.trim().isEmpty()) return;
    if (value.contains("，") || value.contains(";") || value.contains("；")
        || value.startsWith(",") || value.endsWith(",") || value.contains(",,"))
      throw new BusinessException(10008, name + "必须使用英文逗号分隔，且不能有空项");
    for (String token : value.split(","))
      if (!token.trim().matches(tokenPattern))
        throw new BusinessException(10008, name + "中包含非法项：" + token.trim());
  }

  /** 判断字符串是否为 null 或空白。 */
  private boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
