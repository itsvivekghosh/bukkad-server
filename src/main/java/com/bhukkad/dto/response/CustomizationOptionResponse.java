package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomizationOptionResponse {
    private Long id;
    private String name;
    private Boolean required;
    private Boolean multipleSelection;
    private Integer minSelection;
    private Integer maxSelection;
    private List<CustomizationChoiceResponse> choices;
}