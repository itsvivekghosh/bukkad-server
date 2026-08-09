package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MenuCategoryRequest {

    @NotBlank(message = "Category name is required")
    private String name;

    private String description;
    private Integer displayOrder;
    private Boolean active = true;
}