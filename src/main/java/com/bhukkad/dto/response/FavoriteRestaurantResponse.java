package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FavoriteRestaurantResponse {
    private Long restaurantId;
    private String restaurantName;
    private String imageUrl;
    private Double averageRating;
    private Boolean isOpen;
}
