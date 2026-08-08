package com.icbc.scan.center.controller;

import com.icbc.scan.center.common.*;
import com.icbc.scan.center.dto.TaskCreateDTO;
import com.icbc.scan.center.model.ScanTask;
import com.icbc.scan.center.service.TaskService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
  private final TaskService s;

  public TaskController(TaskService s) {
    this.s = s;
  }

  @GetMapping
  public ApiResponse<PageResult<ScanTask>> page(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int pageNum,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ApiResponse.success(s.page(keyword, status, pageNum, pageSize));
  }

  @GetMapping("/{id}")
  public ApiResponse<ScanTask> get(@PathVariable Long id) {
    return ApiResponse.success(s.get(id));
  }

  @PostMapping
  public ApiResponse<Long> create(@Validated @RequestBody TaskCreateDTO d) {
    return ApiResponse.success(s.create(d));
  }

  @PostMapping("/{id}/runs")
  public ApiResponse<Void> execute(@PathVariable Long id) {
    s.execute(id);
    return ApiResponse.success(null);
  }

  @PutMapping("/{id}/cancellation")
  public ApiResponse<Void> cancel(@PathVariable Long id) {
    s.cancel(id);
    return ApiResponse.success(null);
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    s.delete(id);
    return ApiResponse.success(null);
  }
}
