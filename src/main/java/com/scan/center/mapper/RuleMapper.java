package com.scan.center.mapper;

import com.scan.center.model.ScanRule;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RuleMapper {
  List<ScanRule> page(
      @Param("keyword") String keyword,
      @Param("type") String type,
      @Param("enabled") Boolean enabled,
      @Param("offset") int offset,
      @Param("size") int size);

  long count(
      @Param("keyword") String keyword,
      @Param("type") String type,
      @Param("enabled") Boolean enabled);

  ScanRule findById(Long id);

  List<ScanRule> findByIds(@Param("ids") List<Long> ids);

  int insert(ScanRule rule);

  int update(ScanRule rule);

  int updateStatus(@Param("id") Long id, @Param("enabled") Boolean enabled);

  int logicalDelete(Long id);

  int countTaskReference(Long id);
}
