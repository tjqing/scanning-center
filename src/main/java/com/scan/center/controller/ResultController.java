package com.scan.center.controller;

import com.scan.center.common.*;
import com.scan.center.dto.IssueStatusDTO;
import com.scan.center.model.*;
import com.scan.center.service.ResultService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1")
public class ResultController {
  private final ResultService s;

  public ResultController(ResultService s) {
    this.s = s;
  }

  @GetMapping("/results")
  public ApiResponse<PageResult<ScanResult>> results(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String application,
      @RequestParam(required = false) String versionNo,
      @RequestParam(defaultValue = "1") int pageNum,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ApiResponse.success(s.page(keyword, application, versionNo, pageNum, pageSize));
  }

  @GetMapping("/results/{id}")
  public ApiResponse<ScanResult> result(@PathVariable Long id) {
    return ApiResponse.success(s.get(id));
  }

  @GetMapping("/issues")
  public ApiResponse<PageResult<ScanIssue>> issues(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String risk,
      @RequestParam(required = false) Long resultId,
      @RequestParam(required = false) String application,
      @RequestParam(required = false) String versionNo,
      @RequestParam(defaultValue = "1") int pageNum,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ApiResponse.success(s.issues(keyword, status, risk, resultId, application, versionNo, pageNum, pageSize));
  }

  @GetMapping("/issues/{id}")
  public ApiResponse<ScanIssue> issue(@PathVariable Long id) {
    return ApiResponse.success(s.issue(id));
  }

  @GetMapping("/issues/export")
  public ResponseEntity<byte[]> exportIssues(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String risk,
      @RequestParam(required = false) Long resultId,
      @RequestParam(required = false) String application,
      @RequestParam(required = false) String versionNo) throws Exception {
    byte[] content = s.exportIssues(keyword, status, risk, resultId, application, versionNo);
    String filename = URLEncoder.encode("扫描结果明细.xlsx", StandardCharsets.UTF_8.name()).replace("+", "%20");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .contentLength(content.length)
        .body(content);
  }

  @PutMapping("/issues/{id}/status")
  public ApiResponse<Void> status(@PathVariable Long id, @Validated @RequestBody IssueStatusDTO d) {
    s.updateIssue(id, d);
    return ApiResponse.success(null);
  }
}
