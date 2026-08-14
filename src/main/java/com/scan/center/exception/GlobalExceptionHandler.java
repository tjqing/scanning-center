package com.scan.center.exception;

import com.scan.center.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ApiResponse<Void> business(BusinessException e) {
    log.warn("business error code={}, message={}", e.getCode(), e.getMessage());
    return ApiResponse.failure(e.getCode(), e.getMessage());
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> validation(Exception e) {
    String detail = "请求参数不合法";
    if (e instanceof MethodArgumentNotValidException) {
      MethodArgumentNotValidException ex = (MethodArgumentNotValidException) e;
      if (ex.getBindingResult().getFieldError() != null) {
        detail = ex.getBindingResult().getFieldError().getField() + " "
            + ex.getBindingResult().getFieldError().getDefaultMessage();
      }
    } else if (e instanceof BindException) {
      BindException ex = (BindException) e;
      if (ex.getBindingResult().getFieldError() != null) {
        detail = ex.getBindingResult().getFieldError().getField() + " "
            + ex.getBindingResult().getFieldError().getDefaultMessage();
      }
    }
    return ApiResponse.failure(90001, detail);
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ApiResponse<Void> unknown(Exception e) {
    log.error("unexpected error", e);
    return ApiResponse.failure(90000, "系统繁忙，请稍后重试");
  }
}
