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

        List<Map<String, Object>> categoryList = new ArrayList<>();
        for (MenuCategory category : categories) {
            Map<String, Object> categoryMap = new LinkedHashMap<>();
            categoryMap.put("id", category.getId());
            categoryMap.put("name", category.getName());
            categoryMap.put("description", category.getDescription());
            categoryMap.put("displayOrder", category.getDisplayOrder());
            categoryMap.put("active", category.getActive());
            categoryList.add(categoryMap);
        }

        List<Map<String, Object>> itemList = new ArrayList<>();
        for (MenuItem item : items) {
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("id", item.getId());
            itemMap.put("name", item.getName());
            itemMap.put("description", item.getDescription());
            itemMap.put("categoryId", item.getCategory() != null ? item.getCategory().getId() : null);
            itemMap.put("categoryName", item.getCategory() != null ? item.getCategory().getName() : null);
            itemMap.put("price", item.getPrice());
            itemMap.put("originalPrice", item.getOriginalPrice());
            itemMap.put("discountPercentage", item.getDiscountPercentage());
            itemMap.put("available", item.getAvailable());
            itemMap.put("foodType", item.getFoodType() != null ? item.getFoodType().name() : null);
            itemMap.put("isVeg", item.getIsVeg());
            itemMap.put("isSpicy", item.getIsSpicy());
            itemMap.put("spiceLevel", item.getSpiceLevel() != null ? item.getSpiceLevel().name() : null);
            itemMap.put("imageUrl", item.getImageUrl());
            itemMap.put("preparationTime", item.getPreparationTime());
            itemMap.put("bestseller", item.getBestseller());
            itemMap.put("recommended", item.getRecommended());
            itemMap.put("calories", item.getCalories());
            itemMap.put("servingSize", item.getServingSize());
            itemMap.put("averageRating", item.getAverageRating());
            itemMap.put("totalRatings", item.getTotalRatings());
            itemMap.put("stockQuantity", item.getStockQuantity());
            itemMap.put("tags", item.getTags() == null ? List.of() : new ArrayList<>(item.getTags()));
            itemMap.put("allergens", item.getAllergens() == null ? List.of() : new ArrayList<>(item.getAllergens()));
            itemMap.put("ingredients", item.getIngredients() == null ? List.of() : new ArrayList<>(item.getIngredients()));
            itemMap.put("additionalImages", item.getAdditionalImages() == null ? List.of() : new ArrayList<>(item.getAdditionalImages()));
            itemList.add(itemMap);
        }

        snapshot.put("categories", categoryList);
        snapshot.put("items", itemList);
        return snapshot;
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