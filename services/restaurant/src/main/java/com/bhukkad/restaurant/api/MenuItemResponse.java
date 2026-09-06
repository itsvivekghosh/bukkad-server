package com.bhukkad.restaurant.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuItemResponse {
    private Long id;
    private Long restaurantId;
    private String name;
    private String description;
    private String categoryName;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private BigDecimal discountPercentage;
    private Boolean available;
    private String foodType;
    private Boolean isVeg;
    private Boolean isSpicy;
    private String spiceLevel;
    private Set<String> allergens;
    private String imageUrl;
    private List<String> additionalImages;
    private Integer preparationTime;
    private Boolean bestseller;
    private Boolean recommended;
    private Integer calories;
    private String servingSize;
    private Set<String> ingredients;
    private Double averageRating;
    private Integer totalRatings;
    private List<CustomizationOptionResponse> customizationOptions;
    private Set<String> tags;
    private Integer stockQuantity;
    private Double restaurantDistanceKm;
}
