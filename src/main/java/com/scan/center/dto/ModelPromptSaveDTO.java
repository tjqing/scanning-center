package com.scan.center.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class ModelPromptSaveDTO {
  @NotBlank private String promptType;
  @NotBlank @Size(max = 60000) private String promptContent;
  @Size(max = 30000) private String jsonSchema;
}
