package com.scan.center.controller;

import com.scan.center.common.*;
import com.scan.center.dto.TaskCreateDTO;
import com.scan.center.model.ScanTask;
import com.scan.center.model.TaskManifest;
import com.scan.center.model.TaskManifestFile;
import com.scan.center.model.TaskSnapshot;
import java.util.List;
import com.scan.center.service.TaskService;
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

  @PutMapping("/{id}")
  public ApiResponse<Void> update(@PathVariable Long id, @Validated @RequestBody TaskCreateDTO d) {
    s.update(id, d); return ApiResponse.success(null);
  }

  @PostMapping("/{id}/copies")
  public ApiResponse<Long> copy(@PathVariable Long id) {
    return ApiResponse.success(s.copy(id));
  }

  @GetMapping("/{id}/snapshots")
  public ApiResponse<List<TaskSnapshot>> snapshots(@PathVariable Long id) {
    return ApiResponse.success(s.snapshots(id));
  }

  @GetMapping("/{id}/snapshots/{snapshotId}")
  public ApiResponse<TaskSnapshot> snapshot(@PathVariable Long id, @PathVariable Long snapshotId) {
    return ApiResponse.success(s.snapshot(id, snapshotId));
  }

  @GetMapping("/{id}/manifests/current")
  public ApiResponse<TaskManifest> manifest(@PathVariable Long id) {
    return ApiResponse.success(s.currentManifest(id));
  }

  @PostMapping("/{id}/manifests")
  public ApiResponse<Long> regenerateManifest(@PathVariable Long id) {
    return ApiResponse.success(s.regenerateManifest(id));
  }

  @GetMapping("/manifests/{manifestId}/files")
  public ApiResponse<PageResult<TaskManifestFile>> manifestFiles(
      @PathVariable Long manifestId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String fileType,
      @RequestParam(defaultValue = "1") int pageNum,
      @RequestParam(defaultValue = "50") int pageSize) {
    return ApiResponse.success(s.manifestFiles(manifestId, keyword, fileType, pageNum, pageSize));
  }

  @PostMapping("/{id}/runs")
  public ApiResponse<Void> execute(@PathVariable Long id) {
    s.execute(id);
    return ApiResponse.success(null);
  }

  @PutMapping("/{id}/cancellation")
  public ApiResponse<Void> cancel(@PathVariable Long id) {
    s.stop(id);
    return ApiResponse.success(null);
  }

  @PutMapping("/{id}/stop")
  public ApiResponse<Void> stop(@PathVariable Long id) { s.stop(id); return ApiResponse.success(null); }

  @PutMapping("/{id}/resume")
  public ApiResponse<Void> resume(@PathVariable Long id) { s.resume(id); return ApiResponse.success(null); }

  @PutMapping("/{id}/abort")
  public ApiResponse<Void> abort(@PathVariable Long id) { s.abort(id); return ApiResponse.success(null); }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    s.delete(id);
    return ApiResponse.success(null);
  }
}
