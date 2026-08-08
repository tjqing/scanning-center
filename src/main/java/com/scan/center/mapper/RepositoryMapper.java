package com.scan.center.mapper;

import com.scan.center.model.CodeRepository;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RepositoryMapper {
  List<CodeRepository> page(
      @Param("keyword") String keyword,
      @Param("type") String type,
      @Param("enabled") Boolean enabled,
      @Param("offset") int offset,
      @Param("size") int size);

  long count(
      @Param("keyword") String keyword,
      @Param("type") String type,
      @Param("enabled") Boolean enabled);

  CodeRepository findById(Long id);

  int insert(CodeRepository value);

  int update(CodeRepository value);

  int updateFile(
      @Param("id") Long id,
      @Param("storageKey") String storageKey,
      @Param("fileName") String fileName);

  int updateStatus(@Param("id") Long id, @Param("enabled") Boolean enabled);

  int logicalDelete(Long id);

  int countTaskReference(Long id);
}
