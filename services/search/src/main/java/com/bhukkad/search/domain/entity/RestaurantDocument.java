package com.bhukkad.search.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Elasticsearch document for restaurant search.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RestaurantDocument(
        Long id,
        String name,
        String description,
        String imageUrl,
        Boolean isOpen,
        Boolean isActive,
        Double averageRating,
        Integer totalReviews,
        String cuisineSummary,
        Double latitude,
        Double longitude,
        java.util.List<String> tags,
        Integer deliveryRadiusKm,
        Float popularityScore
) {
}
