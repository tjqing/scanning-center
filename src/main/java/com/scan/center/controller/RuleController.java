package com.scan.center.controller;

import com.scan.center.common.*;
import com.scan.center.dto.RuleSaveDTO;
import com.scan.center.model.ScanRule;
import com.scan.center.service.RuleService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {
  private final RuleService s;

  public RuleController(RuleService s) {
    this.s = s;
  }

  @GetMapping
  public ApiResponse<PageResult<ScanRule>> page(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "1") int pageNum,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ApiResponse.success(s.page(keyword, type, enabled, pageNum, pageSize));
  }

  @GetMapping("/{id}")
  public ApiResponse<ScanRule> get(@PathVariable Long id) {
    return ApiResponse.success(s.get(id));
  }

  @PostMapping
  public ApiResponse<Long> create(@Validated @RequestBody RuleSaveDTO d) {
    return ApiResponse.success(s.create(d));
  }

  @PutMapping("/{id}")
  public ApiResponse<Void> update(@PathVariable Long id, @Validated @RequestBody RuleSaveDTO d) {
    s.update(id, d);
    return ApiResponse.success(null);
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    s.delete(id);
    return ApiResponse.success(null);
  }

  @PostMapping("/{id}/copies")
  public ApiResponse<Long> copy(@PathVariable Long id) {
    return ApiResponse.success(s.copy(id));
  }

  @PutMapping("/{id}/status")
  public ApiResponse<Void> status(@PathVariable Long id, @RequestParam boolean enabled) {
    s.status(id, enabled);
    return ApiResponse.success(null);
  }
}
