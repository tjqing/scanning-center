package com.scan.center.mapper;

import com.scan.center.model.*;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TaskMapper {
  List<ScanTask> page(
      @Param("keyword") String keyword,
      @Param("status") String status,
      @Param("application") String application,
      @Param("versionNo") String versionNo,
      @Param("offset") int offset,
      @Param("size") int size);

  long count(
      @Param("keyword") String keyword,
      @Param("status") String status,
      @Param("application") String application,
      @Param("versionNo") String versionNo);

  ScanTask findById(Long id);

  int insert(ScanTask task);

  int insertTaskRule(
      @Param("taskId") Long taskId,
      @Param("ruleId") Long ruleId,
      @Param("snapshot") String snapshot);

  List<ScanRule> findTaskRules(Long taskId);

  int queue(@Param("id") Long id);

  int start(@Param("id") Long id);

  int requestCancel(Long id);

  int updateTotal(@Param("id") Long id, @Param("total") int total);

  int updateProgress(
      @Param("id") Long id, @Param("success") boolean success, @Param("issues") int issues);

  int finish(@Param("id") Long id, @Param("status") String status, @Param("error") String error);

  int deletePending(Long id);

  int insertFile(@Param("taskId") Long taskId, @Param("path") String path);

  int finishFile(
      @Param("taskId") Long taskId,
      @Param("path") String path,
      @Param("status") String status,
      @Param("issues") int issues,
      @Param("error") String error);
}
