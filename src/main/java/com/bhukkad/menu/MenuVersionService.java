package com.bhukkad.menu;

import com.bhukkad.dto.response.MenuVersionResponse;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.MenuVersion;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.repository.MenuCategoryRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.MenuVersionRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MenuVersionService {

    private final MenuVersionRepository menuVersionRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantRepository restaurantRepository;
    private final ObjectMapper objectMapper;

    /**
     * Creates a DRAFT version from the restaurant's current live menu. The live
     * menu is only read (snapshotted); it is never modified by versioning.
     */
    @Transactional
    public MenuVersionResponse createDraft(Long restaurantId, Long ownerId, String label) {
        verifyOwnership(restaurantId, ownerId);

        List<MenuCategory> categories = menuCategoryRepository
                .findByRestaurantIdWithRestaurantOrderByDisplayOrderAsc(restaurantId);
        List<MenuItem> items = menuItemRepository.findByRestaurantIdWithDetails(restaurantId);

        int nextVersionNumber = latestVersionNumber(restaurantId) + 1;

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(buildSnapshot(categories, items));
        } catch (Exception e) {
            log.error("MENU_VERSION | failed to serialize snapshot for restaurant {}", restaurantId, e);
            throw new BusinessException("Failed to create menu version snapshot", e);
        }

        MenuVersion version = new MenuVersion();
        version.setRestaurantId(restaurantId);
        version.setVersionNumber(nextVersionNumber);
        version.setLabel(label);
        version.setSnapshotJson(snapshotJson);
        version.setStatus(MenuVersion.MenuVersionStatus.DRAFT);
        version.setCreatedAt(LocalDateTime.now());
        version.setPublishedAt(null);

        menuVersionRepository.save(version);
        return toResponse(version);
    }

    /**
     * Returns the parsed snapshot JSON of a version for previewing.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(Long versionId, Long ownerId) {
        MenuVersion version = getOwnedVersion(versionId, ownerId);
        try {
            return objectMapper.readValue(version.getSnapshotJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.error("MENU_VERSION | failed to parse snapshot for version {}", versionId, e);
            throw new BusinessException("Menu version snapshot is corrupt", e);
        }
    }

    /**
     * Marks a version as PUBLISHED and stamps published_at. This only affects the
     * versioning feature surface; the live menu read by the order flow is unchanged.
     */
    @Transactional
    public MenuVersionResponse publish(Long versionId, Long ownerId) {
        MenuVersion version = getOwnedVersion(versionId, ownerId);
        version.setStatus(MenuVersion.MenuVersionStatus.PUBLISHED);
        version.setPublishedAt(LocalDateTime.now());
        menuVersionRepository.save(version);
        return toResponse(version);
    }

    @Transactional(readOnly = true)
    public List<MenuVersionResponse> listVersions(Long restaurantId, Long ownerId) {
        verifyOwnership(restaurantId, ownerId);
        return menuVersionRepository.findByRestaurantIdOrderByVersionNumberDesc(restaurantId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Helper exposing the highest-numbered version for a restaurant.
     */
    public Optional<MenuVersion> latestVersion(Long restaurantId) {
        return menuVersionRepository.findTopByRestaurantIdOrderByVersionNumberDesc(restaurantId);
    }

    private int latestVersionNumber(Long restaurantId) {
        return latestVersion(restaurantId)
                .map(MenuVersion::getVersionNumber)
                .orElse(0);
    }

    private MenuVersion getOwnedVersion(Long versionId, Long ownerId) {
        MenuVersion version = menuVersionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException("Menu version not found"));
        verifyOwnership(version.getRestaurantId(), ownerId);
        return version;
    }

    private Restaurant verifyOwnership(Long restaurantId, Long ownerId) {
        Restaurant restaurant = restaurantRepository.findByIdWithDetails(restaurantId)
                .orElseThrow(() -> new BusinessException("Restaurant not found"));
        if (restaurant.getOwner() == null || !restaurant.getOwner().getId().equals(ownerId)) {
            throw new BusinessException("You don't own this restaurant");
        }
        return restaurant;
    }

    private Map<String, Object> buildSnapshot(List<MenuCategory> categories, List<MenuItem> items) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("categories", categories.stream().map(this::toCategoryMap).toList());
        snapshot.put("items", items.stream().map(this::toItemMap).toList());
        return snapshot;
    }

    private Map<String, Object> toCategoryMap(MenuCategory category) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", category.getId());
        map.put("name", category.getName());
        map.put("description", category.getDescription());
        map.put("displayOrder", category.getDisplayOrder());
        map.put("active", category.getActive());
        return map;
    }

    private Map<String, Object> toItemMap(MenuItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("name", item.getName());
        map.put("description", item.getDescription());
        map.put("categoryId", item.getCategory() != null ? item.getCategory().getId() : null);
        map.put("categoryName", item.getCategory() != null ? item.getCategory().getName() : null);
        map.put("price", item.getPrice());
        map.put("originalPrice", item.getOriginalPrice());
        map.put("discountPercentage", item.getDiscountPercentage());
        map.put("available", item.getAvailable());
        map.put("foodType", item.getFoodType() != null ? item.getFoodType().name() : null);
        map.put("isVeg", item.getIsVeg());
        map.put("isSpicy", item.getIsSpicy());
        map.put("spiceLevel", item.getSpiceLevel() != null ? item.getSpiceLevel().name() : null);
        map.put("imageUrl", item.getImageUrl());
        map.put("preparationTime", item.getPreparationTime());
        map.put("bestseller", item.getBestseller());
        map.put("recommended", item.getRecommended());
        map.put("calories", item.getCalories());
        map.put("servingSize", item.getServingSize());
        map.put("averageRating", item.getAverageRating());
        map.put("totalRatings", item.getTotalRatings());
        map.put("stockQuantity", item.getStockQuantity());
        map.put("tags", safeCopySet(item.getTags()));
        map.put("allergens", safeCopySet(item.getAllergens()));
        map.put("ingredients", safeCopySet(item.getIngredients()));
        map.put("additionalImages", safeCopy(item.getAdditionalImages()));
        return map;
    }

    private static <T> List<T> safeCopy(List<T> source) {
        return source == null ? List.of() : new ArrayList<>(source);
    }

    private static <T> List<T> safeCopySet(java.util.Set<T> source) {
        return source == null ? List.of() : new ArrayList<>(source);
    }

    private MenuVersionResponse toResponse(MenuVersion version) {
        return new MenuVersionResponse(
                version.getId(),
                version.getRestaurantId(),
                version.getVersionNumber(),
                version.getLabel(),
                version.getStatus() != null ? version.getStatus().name() : null,
                version.getCreatedAt(),
                version.getPublishedAt()
        );
    }
}