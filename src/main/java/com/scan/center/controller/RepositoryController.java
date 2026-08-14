package com.scan.center.controller;

import com.scan.center.common.*;
import com.scan.center.dto.RepositorySaveDTO;
import com.scan.center.model.CodeRepository;
import com.scan.center.service.RepositoryService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {
  private final RepositoryService s;

  public RepositoryController(RepositoryService s) {
    this.s = s;
  }

  @GetMapping
  public ApiResponse<PageResult<CodeRepository>> page(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String application,
      @RequestParam(required = false) String versionNo,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "1") int pageNum,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ApiResponse.success(s.page(keyword, type, application, versionNo, enabled, pageNum, pageSize));
  }

  @GetMapping("/{id}")
  public ApiResponse<CodeRepository> get(@PathVariable Long id) {
    return ApiResponse.success(s.get(id));
  }

  @PostMapping
  public ApiResponse<Long> create(@Validated @RequestBody RepositorySaveDTO d) {
    return ApiResponse.success(s.create(d));
  }

  @PutMapping("/{id}")
  public ApiResponse<Void> update(
      @PathVariable Long id, @Validated @RequestBody RepositorySaveDTO d) {
    s.update(id, d);
    return ApiResponse.success(null);
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    s.delete(id);
    return ApiResponse.success(null);
  }

  @PutMapping("/{id}/status")
  public ApiResponse<Void> status(@PathVariable Long id, @RequestParam boolean enabled) {
    s.status(id, enabled);
    return ApiResponse.success(null);
  }

  @PostMapping("/{id}/files")
  public ApiResponse<Void> upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
    s.upload(id, file);
    return ApiResponse.success(null);
  }

  @GetMapping("/md/versions")
  public ApiResponse<java.util.List<String>> mdVersions(@RequestParam String documentType, @RequestParam String application) {
    return ApiResponse.success(s.mdVersions(documentType, application));
  }
}
