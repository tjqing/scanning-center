package com.scan.center.controller;

import com.scan.center.common.ApiResponse;
import com.scan.center.dto.ModelCredentialSaveDTO;
import com.scan.center.dto.ModelPromptSaveDTO;
import com.scan.center.model.ModelCredential;
import com.scan.center.model.ModelPromptTemplate;
import com.scan.center.service.ModelConfigService;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ModelConfigController {
  private final ModelConfigService service;

  public ModelConfigController(ModelConfigService service) {
    this.service = service;
  }

  @GetMapping("/model-credentials")
  public ApiResponse<List<ModelCredential>> credentials() {
    return ApiResponse.success(service.credentials());
  }

  @PostMapping("/model-credentials")
  public ApiResponse<Long> createCredential(@Validated @RequestBody ModelCredentialSaveDTO dto) {
    return ApiResponse.success(service.createCredential(dto));
  }

  @PutMapping("/model-credentials/{id}")
  public ApiResponse<Void> updateCredential(@PathVariable Long id, @Validated @RequestBody ModelCredentialSaveDTO dto) {
    service.updateCredential(id, dto); return ApiResponse.success(null);
  }

  @DeleteMapping("/model-credentials/{id}")
  public ApiResponse<Void> deleteCredential(@PathVariable Long id) {
    service.deleteCredential(id); return ApiResponse.success(null);
  }

  @PutMapping("/model-credentials/{id}/status")
  public ApiResponse<Void> credentialStatus(@PathVariable Long id, @RequestParam boolean enabled) {
    service.credentialStatus(id, enabled); return ApiResponse.success(null);
  }

  @PostMapping("/model-credentials/{id}/connection-test")
  public ApiResponse<String> credentialTest(@PathVariable Long id) {
    return ApiResponse.success(service.testCredential(id));
  }

  @GetMapping("/model-settings/token-retry-count")
  public ApiResponse<Integer> tokenRetryCount() { return ApiResponse.success(service.tokenRetryCount()); }

  @PutMapping("/model-settings/token-retry-count")
  public ApiResponse<Void> updateTokenRetryCount(@RequestParam int retryCount) {
    service.updateTokenRetryCount(retryCount); return ApiResponse.success(null);
  }

  @GetMapping("/model-prompts")
  public ApiResponse<List<ModelPromptTemplate>> prompts(@RequestParam(required = false) String type) {
    return ApiResponse.success(service.prompts(type));
  }

  @PostMapping("/model-prompts")
  public ApiResponse<Long> createPrompt(@Validated @RequestBody ModelPromptSaveDTO dto) {
    return ApiResponse.success(service.createPrompt(dto));
  }

  @PutMapping("/model-prompts/{id}")
  public ApiResponse<Void> updatePrompt(@PathVariable Long id, @Validated @RequestBody ModelPromptSaveDTO dto) {
    service.updatePrompt(id, dto); return ApiResponse.success(null);
  }

  @PutMapping("/model-prompts/{id}/activation")
  public ApiResponse<Void> activatePrompt(@PathVariable Long id) {
    service.activatePrompt(id); return ApiResponse.success(null);
  }
}
