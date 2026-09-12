package com.bhukkad.restaurant.domain.mapper;

import com.bhukkad.restaurant.api.dto.response.MenuCategoryResponse;
import com.bhukkad.restaurant.api.dto.response.MenuItemResponse;
import com.bhukkad.restaurant.api.dto.response.RestaurantResponse;
import com.bhukkad.restaurant.domain.entity.Cuisine;
import com.bhukkad.restaurant.domain.entity.MenuCategory;
import com.bhukkad.restaurant.domain.entity.MenuItem;
import com.bhukkad.restaurant.domain.entity.Restaurant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public abstract class RestaurantMapper {

    @Autowired
    protected ImageUrlResolver imageUrlResolver;

    @Mapping(target = "cuisineId", source = "cuisine.id")
    @Mapping(target = "imageUrl", expression = "java(resolveImageUrl(restaurant.getImageUrl()))")
    @Mapping(target = "galleryImages", expression = "java(resolveGalleryImages(restaurant.getGalleryImages()))")
    @Mapping(target = "foodTypes", expression = "java(resolveFoodTypes(restaurant.getFoodTypes()))")
    @Mapping(target = "features", expression = "java(resolveFeatures(restaurant.getFeatures()))")
    @Mapping(target = "onboardingStatus", expression = "java(resolveOnboardingStatus(restaurant.getOnboardingStatus()))")
    public abstract RestaurantResponse toResponse(Restaurant restaurant);

    @Mapping(target = "categoryName", source = "category", ignore = true)
    @Mapping(target = "foodType", expression = "java(enumName(menuItem.getFoodType()))")
    @Mapping(target = "spiceLevel", expression = "java(enumName(menuItem.getSpiceLevel()))")
    @Mapping(target = "imageUrl", ignore = true)
    @Mapping(target = "additionalImages", ignore = true)
    @Mapping(target = "customizationOptions", ignore = true)
    @Mapping(target = "restaurantDistanceKm", ignore = true)
    public abstract MenuItemResponse toResponse(MenuItem menuItem);

    public MenuItemResponse resolveImageUrls(MenuItem source, MenuItemResponse target) {
        target.setImageUrl(imageUrlResolver.resolvePublicUrl(source.getImageUrl()));
        if (source.getAdditionalImages() != null) {
            target.setAdditionalImages(source.getAdditionalImages().stream()
                    .map(imageUrlResolver::resolvePublicUrl)
                    .collect(Collectors.toList()));
        }
        return target;
    }

    public MenuCategoryResponse toCategoryResponse(MenuCategory category) {
        return MenuCategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .restaurantId(category.getRestaurantId())
                .displayOrder(category.getDisplayOrder())
                .active(category.getActive())
                .build();
    }

    public List<MenuCategoryResponse> toCategoryResponses(List<MenuCategory> categories) {
        return categories.stream().map(this::toCategoryResponse).collect(Collectors.toList());
    }

    protected String enumName(Enum<?> value) {
        return value != null ? value.name() : null;
    }

    protected String resolveImageUrl(String url) {
        return url != null ? imageUrlResolver.resolvePublicUrl(url) : null;
    }

    protected List<String> resolveGalleryImages(Set<String> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream()
                .map(imageUrlResolver::resolvePublicUrl)
                .collect(Collectors.toList());
    }

    protected Set<String> resolveFoodTypes(Set<com.bhukkad.restaurant.domain.entity.Restaurant.FoodType> foodTypes) {
        if (foodTypes == null || foodTypes.isEmpty()) {
            return Set.of();
        }
        return foodTypes.stream().map(Enum::name).collect(Collectors.toSet());
    }

    protected Set<String> resolveFeatures(Set<String> features) {
        return features != null ? features : Set.of();
    }

    protected String resolveOnboardingStatus(com.bhukkad.restaurant.domain.entity.Restaurant.OnboardingStatus status) {
        return status != null ? status.name() : null;
    }
}
