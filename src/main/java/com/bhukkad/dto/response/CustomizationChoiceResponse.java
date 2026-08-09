package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomizationChoiceResponse {
    private Long id;
    private String name;
    private Double additionalPrice;
    private Boolean available;
}