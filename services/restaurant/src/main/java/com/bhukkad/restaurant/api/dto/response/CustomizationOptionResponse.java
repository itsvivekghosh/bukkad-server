package com.bhukkad.restaurant.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Menu customization option contract (ported from the monolith
 * {@code com.bhukkad.dto.response.CustomizationOptionResponse} during migration W3 —
 * restaurant owns menu customization payloads).
 */
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
