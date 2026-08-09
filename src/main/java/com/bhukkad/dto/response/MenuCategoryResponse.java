package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuCategoryResponse {
    private Long id;
    private String name;
    private String description;
    private Long restaurantId;
    private Integer displayOrder;
    private Boolean active;
    private Integer itemCount;
}