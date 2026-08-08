package com.scan.center.dto;

import javax.validation.constraints.*;
import lombok.Data;

@Data
public class IssueStatusDTO {
  @NotBlank private String status;

  @Size(max = 2000)
  private String comment;
}
