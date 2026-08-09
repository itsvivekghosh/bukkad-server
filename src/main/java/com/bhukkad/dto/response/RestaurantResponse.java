package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantResponse {
    private Long id;
    private String name;
    private String description;
    private AddressResponse address;
    private Set<String> cuisines;
    private String imageUrl;
    private List<String> galleryImages;
    private LocalTime openingTime;
    private LocalTime closingTime;
    private Boolean isOpen;
    private Boolean isActive;
    private Double averageRating;
    private Integer totalReviews;
    private Integer averageDeliveryTime;
    private Double minimumOrderAmount;
    private Double deliveryFee;
    private Boolean freeDeliveryAvailable;
    private Double freeDeliveryAbove;
    private Boolean isPureVeg;
    private Set<String> foodTypes;
    private Set<String> features;
}