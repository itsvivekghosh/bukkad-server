package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

@Data
public class RestaurantRequest {
    @NotBlank(message = "Restaurant name is required")
    private String name;

    private String description;

    @NotNull(message = "Address is required")
    private AddressRequest address;

    private Set<Long> cuisineIds;

    private String imageUrl;

    private List<String> galleryImages;

    @NotNull(message = "Opening time is required")
    private LocalTime openingTime;

    @NotNull(message = "Closing time is required")
    private LocalTime closingTime;

    private Integer averageDeliveryTime;

    private Double minimumOrderAmount;

    private Double deliveryFee;

    private Boolean freeDeliveryAvailable;

    private Double freeDeliveryAbove;

    private Boolean isPureVeg;

    private Set<String> foodTypes;

    private Set<String> features;

    private String licenseNumber;

    private String fssaiNumber;
}