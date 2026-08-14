package com.bhukkad.dto.request;

import com.bhukkad.entity.MenuItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
public class MenuItemRequest {
    @NotBlank(message = "Item name is required")
    private String name;

    private String description;

    @NotNull(message = "Category ID is required")
    private Long categoryId;

    @NotNull(message = "Price is required")
    @Positive(message = "Price must be positive")
    private Double price;

    private Double originalPrice;

    @NotNull(message = "Food type is required")
    private MenuItem.FoodType foodType;

    @NotNull(message = "Veg status is required")
    private Boolean isVeg;

    private Boolean isSpicy;

    private MenuItem.SpiceLevel spiceLevel;

    private Set<String> allergens;

    private String imageUrl;

    /** S3 object key returned from the image upload-url endpoint (preferred over imageUrl). */
    private String imageKey;

    private List<String> additionalImages;

    private Integer preparationTime;

    private Integer calories;

    private String servingSize;

    private Set<String> ingredients;

    private Set<String> tags;

    private List<CustomizationOptionRequest> customizationOptions;
}