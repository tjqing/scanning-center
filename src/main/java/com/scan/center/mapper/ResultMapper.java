package com.scan.center.mapper;

import com.scan.center.model.*;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ResultMapper {
  int insertResult(ScanResult result);

  int deleteIssuesByTask(Long taskId);

  int deleteResultByTask(Long taskId);

  ScanResult findByTaskId(Long taskId);

  ScanResult findById(Long id);

  List<ScanResult> page(
      @Param("keyword") String keyword,
      @Param("application") String application,
      @Param("versionNo") String versionNo,
      @Param("offset") int offset,
      @Param("size") int size);

  long count(
      @Param("keyword") String keyword,
      @Param("application") String application,
      @Param("versionNo") String versionNo);

  int insertIssue(ScanIssue issue);

  List<ScanIssue> issuePage(
      @Param("keyword") String keyword,
      @Param("status") String status,
      @Param("risk") String risk,
      @Param("resultId") Long resultId,
      @Param("application") String application,
      @Param("versionNo") String versionNo,
      @Param("offset") int offset,
      @Param("size") int size);

  long issueCount(
      @Param("keyword") String keyword,
      @Param("status") String status,
      @Param("risk") String risk,
      @Param("resultId") Long resultId,
      @Param("application") String application,
      @Param("versionNo") String versionNo);

  List<ScanIssue> issueExport(
      @Param("keyword") String keyword,
      @Param("status") String status,
      @Param("risk") String risk,
      @Param("resultId") Long resultId,
      @Param("application") String application,
      @Param("versionNo") String versionNo);

  ScanIssue findIssue(Long id);

  int updateIssue(
      @Param("id") Long id,
      @Param("status") String status,
      @Param("comment") String comment,
      @Param("handler") String handler);

  int refreshSummary(Long taskId);
}
