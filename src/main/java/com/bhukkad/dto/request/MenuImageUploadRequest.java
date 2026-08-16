package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MenuImageUploadRequest {

    @NotBlank(message = "Content type is required")
    private String contentType;
}
