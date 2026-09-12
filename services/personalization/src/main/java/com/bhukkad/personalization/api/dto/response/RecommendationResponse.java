package com.bhukkad.personalization.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationResponse {
    private Long itemId;
    private String name;
    private String description;
    private Double price;
    private Long restaurantId;
    private String restaurantName;
    private String category;
    private Double rating;
    private String imageUrl;
}
