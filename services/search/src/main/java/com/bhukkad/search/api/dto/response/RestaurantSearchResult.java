package com.bhukkad.search.api.dto.response;

public class RestaurantSearchResult {
    private Long id;
    private String name;
    private String description;
    private String imageUrl;
    private Boolean isOpen;
    private Boolean isActive;
    private Double averageRating;
    private Integer totalReviews;
    private String cuisineSummary;
    private Double distanceKm;

    public RestaurantSearchResult() {}

    public RestaurantSearchResult(Long id, String name, String description, String imageUrl, Boolean isOpen,
                                  Boolean isActive, Double averageRating, Integer totalReviews,
                                  String cuisineSummary, Double distanceKm) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.imageUrl = imageUrl;
        this.isOpen = isOpen;
        this.isActive = isActive;
        this.averageRating = averageRating;
        this.totalReviews = totalReviews;
        this.cuisineSummary = cuisineSummary;
        this.distanceKm = distanceKm;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public Boolean getIsOpen() { return isOpen; }
    public void setIsOpen(Boolean isOpen) { this.isOpen = isOpen; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Double getAverageRating() { return averageRating; }
    public void setAverageRating(Double averageRating) { this.averageRating = averageRating; }
    public Integer getTotalReviews() { return totalReviews; }
    public void setTotalReviews(Integer totalReviews) { this.totalReviews = totalReviews; }
    public String getCuisineSummary() { return cuisineSummary; }
    public void setCuisineSummary(String cuisineSummary) { this.cuisineSummary = cuisineSummary; }
    public Double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }
}
