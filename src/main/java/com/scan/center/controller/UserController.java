package com.scan.center.controller;

import com.scan.center.common.*;
import com.scan.center.dto.UserSaveDTO;
import com.scan.center.model.SystemUser;
import com.scan.center.service.UserService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/users")
public class UserController {
  private final UserService service; public UserController(UserService service){this.service=service;}
  @GetMapping public ApiResponse<PageResult<SystemUser>> page(@RequestParam(required=false)String keyword,@RequestParam(required=false)String roleCode,@RequestParam(required=false)Boolean enabled,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return ApiResponse.success(service.page(keyword,roleCode,enabled,pageNum,pageSize));}
  @GetMapping("/{id}") public ApiResponse<SystemUser> get(@PathVariable Long id){return ApiResponse.success(service.get(id));}
  @PostMapping public ApiResponse<Long> create(@Validated @RequestBody UserSaveDTO dto){return ApiResponse.success(service.create(dto));}
  @PutMapping("/{id}") public ApiResponse<Void> update(@PathVariable Long id,@Validated @RequestBody UserSaveDTO dto){service.update(id,dto);return ApiResponse.success(null);}
  @PutMapping("/{id}/status") public ApiResponse<Void> status(@PathVariable Long id,@RequestParam boolean enabled){service.status(id,enabled);return ApiResponse.success(null);}
  @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable Long id){service.delete(id);return ApiResponse.success(null);}
}
