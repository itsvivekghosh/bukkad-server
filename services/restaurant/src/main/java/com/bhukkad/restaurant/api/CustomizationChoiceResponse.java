package com.bhukkad.restaurant.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Menu customization choice contract (ported from the monolith
 * {@code com.bhukkad.dto.response.CustomizationChoiceResponse} during migration W3).
 */
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
