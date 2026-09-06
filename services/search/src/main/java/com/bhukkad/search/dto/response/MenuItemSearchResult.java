package com.bhukkad.search.dto.response;

public class MenuItemSearchResult {
    private Long id;
    private String name;
    private String description;
    private String categoryName;
    private Double price;
    private Double originalPrice;
    private Double discountPercentage;
    private Boolean available;
    private String foodType;
    private Boolean isVeg;
    private String imageUrl;
    private Integer preparationTime;
    private Boolean bestseller;
    private String restaurantName;
    private Double restaurantDistanceKm;

    public MenuItemSearchResult() {}

    public MenuItemSearchResult(Long id, String name, String description, String categoryName, Double price,
                                Double originalPrice, Double discountPercentage, Boolean available,
                                String foodType, Boolean isVeg, String imageUrl, Integer preparationTime,
                                Boolean bestseller, String restaurantName, Double restaurantDistanceKm) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.categoryName = categoryName;
        this.price = price;
        this.originalPrice = originalPrice;
        this.discountPercentage = discountPercentage;
        this.available = available;
        this.foodType = foodType;
        this.isVeg = isVeg;
        this.imageUrl = imageUrl;
        this.preparationTime = preparationTime;
        this.bestseller = bestseller;
        this.restaurantName = restaurantName;
        this.restaurantDistanceKm = restaurantDistanceKm;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
    public Double getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(Double originalPrice) { this.originalPrice = originalPrice; }
    public Double getDiscountPercentage() { return discountPercentage; }
    public void setDiscountPercentage(Double discountPercentage) { this.discountPercentage = discountPercentage; }
    public Boolean getAvailable() { return available; }
    public void setAvailable(Boolean available) { this.available = available; }
    public String getFoodType() { return foodType; }
    public void setFoodType(String foodType) { this.foodType = foodType; }
    public Boolean getIsVeg() { return isVeg; }
    public void setIsVeg(Boolean isVeg) { this.isVeg = isVeg; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public Integer getPreparationTime() { return preparationTime; }
    public void setPreparationTime(Integer preparationTime) { this.preparationTime = preparationTime; }
    public Boolean getBestseller() { return bestseller; }
    public void setBestseller(Boolean bestseller) { this.bestseller = bestseller; }
    public String getRestaurantName() { return restaurantName; }
    public void setRestaurantName(String restaurantName) { this.restaurantName = restaurantName; }
    public Double getRestaurantDistanceKm() { return restaurantDistanceKm; }
    public void setRestaurantDistanceKm(Double restaurantDistanceKm) { this.restaurantDistanceKm = restaurantDistanceKm; }
}
