package com.scan.center.controller;

import com.scan.center.common.ApiResponse;
import com.scan.center.common.PageResult;
import com.scan.center.dto.RepositoryCatalogSaveDTO;
import com.scan.center.model.RepositoryCatalog;
import com.scan.center.service.RepositoryCatalogService;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/repository-catalogs")
public class RepositoryCatalogController {
  private final RepositoryCatalogService service;
  public RepositoryCatalogController(RepositoryCatalogService service) { this.service = service; }

  @GetMapping public ApiResponse<PageResult<RepositoryCatalog>> page(@RequestParam(required=false) String keyword,
      @RequestParam(required=false) String application, @RequestParam(required=false) Boolean enabled,
      @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize) {
    return ApiResponse.success(service.page(keyword, application, enabled, pageNum, pageSize));
  }
  @GetMapping("/available") public ApiResponse<List<RepositoryCatalog>> available(@RequestParam String application) { return ApiResponse.success(service.available(application)); }
  @GetMapping("/mine") public ApiResponse<List<RepositoryCatalog>> mine() { return ApiResponse.success(service.mine()); }
  @GetMapping("/{id}/branches") public ApiResponse<List<String>> branches(@PathVariable Long id) { return ApiResponse.success(service.branches(id)); }
  @GetMapping("/{id}") public ApiResponse<RepositoryCatalog> get(@PathVariable Long id) { return ApiResponse.success(service.get(id)); }
  @PostMapping public ApiResponse<Long> create(@Validated @RequestBody RepositoryCatalogSaveDTO dto) { return ApiResponse.success(service.create(dto)); }
  @PutMapping("/{id}") public ApiResponse<Void> update(@PathVariable Long id, @Validated @RequestBody RepositoryCatalogSaveDTO dto) { service.update(id, dto); return ApiResponse.success(null); }
  @PutMapping("/{id}/status") public ApiResponse<Void> status(@PathVariable Long id, @RequestParam boolean enabled) { service.status(id, enabled); return ApiResponse.success(null); }
  @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable Long id) { service.delete(id); return ApiResponse.success(null); }
}
