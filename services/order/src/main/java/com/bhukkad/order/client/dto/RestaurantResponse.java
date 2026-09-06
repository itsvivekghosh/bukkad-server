package com.bhukkad.order.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Minimal restaurant profile response from the Restaurant service.
 * Used by the order service for service-to-service communication.
 * Aliases cover the renamed fields on the RestaurantSummary read model.
 */
public class RestaurantResponse {
    private Long id;
    private String name;
    private String cuisineType;
    @JsonAlias("avgRating")
    private Double averageRating;
    @JsonAlias("active")
    private Boolean isActive;

    public RestaurantResponse() {
    }

    public RestaurantResponse(Long id, String name, String cuisineType, Double averageRating, Boolean isActive) {
        this.id = id;
        this.name = name;
        this.cuisineType = cuisineType;
        this.averageRating = averageRating;
        this.isActive = isActive;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCuisineType() {
        return cuisineType;
    }

    public Double getAverageRating() {
        return averageRating;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setCuisineType(String cuisineType) {
        this.cuisineType = cuisineType;
    }

    public void setAverageRating(Double averageRating) {
        this.averageRating = averageRating;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}
