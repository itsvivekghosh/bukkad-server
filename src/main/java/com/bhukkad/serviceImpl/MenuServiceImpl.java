package com.bhukkad.serviceImpl;

import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.config.InventoryProperties;
import com.bhukkad.inventory.StockReservationService;
import com.bhukkad.datasource.UseReadReplica;
import com.bhukkad.dto.request.MenuImageUploadRequest;
import com.bhukkad.dto.request.MenuCategoryRequest;
import com.bhukkad.dto.request.MenuItemRequest;
import com.bhukkad.dto.response.MenuCategoryResponse;
import com.bhukkad.dto.response.MenuImageUploadResponse;
import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.mapper.MenuItemMapper;
import com.bhukkad.repository.MenuCategoryRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.OrderItemRepository;
import com.bhukkad.search.AutocompleteService;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.MenuService;
import com.bhukkad.storage.ImageStorageProperties;
import com.bhukkad.storage.MenuImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuServiceImpl implements MenuService {

    private final MenuItemRepository menuItemRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final RestaurantRepository restaurantRepository;
    private final SecurityUtils securityUtils;
    private final RedisCacheService cacheService;
    private final MenuItemMapper menuItemMapper;
    private final MenuImageService menuImageService;
    private final ImageStorageProperties imageStorageProperties;
    private final InventoryProperties inventoryProperties;
    private final StockReservationService stockReservationService;
    private final OrderItemRepository orderItemRepository;
    private final AutocompleteService autocompleteService;

    @Value("${cache.ttl.menu-item:900}")
    private long menuItemTtl;

    @Value("${cache.ttl.menu-category:1800}")
    private long menuCategoryTtl;

    @Value("${cache.ttl.search:300}")
    private long searchTtl;

    // ==================== CATEGORY ====================

    @Override
    @Transactional
    public MenuCategoryResponse createCategory(Long restaurantId, MenuCategoryRequest request) {
        Restaurant restaurant = restaurantRepository.findByIdWithDetails(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        verifyOwnership(restaurant);

        MenuCategory category = new MenuCategory();
        category.setRestaurant(restaurant);
        category.setName(request.getName());
        category.setDescription(request.getDescription());
        category.setDisplayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0);
        category.setActive(request.getActive() != null ? request.getActive() : true);

        category = menuCategoryRepository.save(category);
        cacheService.deletePattern("menu-category");

        return mapToCategoryResponse(category);
    }

    @Override
    @UseReadReplica
    public List<MenuCategoryResponse> getCategoriesByRestaurant(Long restaurantId) {
        String cacheKey = CacheKeyGenerator.menuCategoriesByRestaurant(restaurantId);
        return cacheService.getListOrCompute(cacheKey, MenuCategoryResponse.class, menuCategoryTtl, () ->
                menuCategoryRepository.findByRestaurantIdWithRestaurantOrderByDisplayOrderAsc(restaurantId)
                        .stream()
                        .map(this::mapToCategoryResponse)
                        .collect(Collectors.toList()));
    }

    @Override
    @Transactional
    public MenuCategoryResponse updateCategory(Long categoryId, MenuCategoryRequest request) {
        MenuCategory category = menuCategoryRepository.findByIdWithRestaurant(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        verifyOwnership(category.getRestaurant());

        if (request.getName() != null) category.setName(request.getName());
        if (request.getDescription() != null) category.setDescription(request.getDescription());
        if (request.getDisplayOrder() != null) category.setDisplayOrder(request.getDisplayOrder());
        if (request.getActive() != null) category.setActive(request.getActive());

        category = menuCategoryRepository.save(category);
        cacheService.deletePattern("menu-category");
        return mapToCategoryResponse(category);
    }

@Override
    @Transactional
    public void deleteCategory(Long categoryId) {
        MenuCategory category = menuCategoryRepository.findByIdWithRestaurant(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        verifyOwnership(category.getRestaurant());

        long itemCount = menuItemRepository.countByCategoryId(categoryId);
        if (itemCount > 0) {
            throw new BusinessException("Cannot delete category because it contains menu items");
        }

        try {
            menuCategoryRepository.delete(category);
        } catch (DataAccessException e) {
            throw new BusinessException("Cannot delete category due to dependencies: " + e.getMostSpecificCause().getMessage());
        }
        cacheService.deletePattern("menu-category");
        cacheService.deletePattern("menu-item");
    }

    // ==================== MENU ITEMS ====================

    @Override
    @UseReadReplica
    public MenuItemResponse getMenuItemById(Long id) {
        String cacheKey = CacheKeyGenerator.menuItem(id);
        return cacheService.getOrCompute(cacheKey, MenuItemResponse.class, menuItemTtl, () -> {
            MenuItem menuItem = menuItemRepository.findByIdWithDetails(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
            return menuItemMapper.resolveImageUrls(menuItem, menuItemMapper.toResponse(menuItem));
        });
    }

    @Override
    @UseReadReplica
    public List<MenuItemResponse> getMenuItemsByRestaurant(Long restaurantId) {
        String cacheKey = CacheKeyGenerator.menuItemsByRestaurant(restaurantId);
        return cacheService.getListOrCompute(cacheKey, MenuItemResponse.class, menuItemTtl, () ->
                menuItemRepository.findByRestaurantIdWithDetails(restaurantId)
                        .stream()
                        .map(menuItemMapper::toResponse)
                        .collect(Collectors.toList()));
    }

    @Override
    @UseReadReplica
    public List<MenuItemResponse> getMenuItemsByCategory(Long categoryId) {
        String cacheKey = CacheKeyGenerator.menuItemsByCategory(categoryId);
        return cacheService.getListOrCompute(cacheKey, MenuItemResponse.class, menuItemTtl, () ->
                menuItemRepository.findByCategoryIdWithDetails(categoryId)
                        .stream()
                        .map(menuItemMapper::toResponse)
                        .collect(Collectors.toList()));
    }

    @Override
    @UseReadReplica
    public List<MenuItemResponse> getBestsellers(Long restaurantId) {
        String cacheKey = CacheKeyGenerator.bestsellers(restaurantId);
        return cacheService.getListOrCompute(cacheKey, MenuItemResponse.class, menuItemTtl, () ->
                menuItemRepository.findBestsellersWithDetails(restaurantId)
                        .stream()
                        .map(menuItemMapper::toResponse)
                        .collect(Collectors.toList()));
    }

    @Override
    @UseReadReplica
    public List<MenuItemResponse> getRecommended(Long restaurantId) {
        String cacheKey = CacheKeyGenerator.recommended(restaurantId);
        return cacheService.getListOrCompute(cacheKey, MenuItemResponse.class, menuItemTtl, () ->
                getMenuItemsByRestaurant(restaurantId).stream()
                        .filter(item -> Boolean.TRUE.equals(item.getRecommended()))
                        .collect(Collectors.toList()));
    }

    @Override
    @UseReadReplica
    public List<MenuItemResponse> searchMenuItems(String keyword) {
        String cacheKey = CacheKeyGenerator.menuSearch(keyword);
        return cacheService.getListOrCompute(cacheKey, MenuItemResponse.class, searchTtl, () -> {
            List<MenuItem> found;
            try {
                found = menuItemRepository.fullTextSearch(keyword.trim());
                if (found.isEmpty()) {
                    found = menuItemRepository.searchByNameWithDetails(keyword);
                }
            } catch (Exception ex) {
                log.debug("MENU_FULLTEXT_FALLBACK | keyword={}", keyword);
                found = menuItemRepository.searchByNameWithDetails(keyword);
            }
            // Batch-fetch lazy associations for all results in ONE query instead of
            // one findByIdWithDetails per item (N+1).
            List<Long> ids = found.stream().map(MenuItem::getId).toList();
            if (ids.isEmpty()) {
                return List.of();
            }
            Map<Long, MenuItem> byId = menuItemRepository.findAllByIdsWithDetails(ids).stream()
                    .collect(Collectors.toMap(MenuItem::getId, m -> m));
            return ids.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .map(menuItemMapper::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public List<MenuItemResponse> getLowStockItems(Long restaurantId, Integer threshold) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        verifyOwnership(restaurant);
        int effectiveThreshold = threshold != null ? threshold : inventoryProperties.getLowStockThreshold();
        return menuItemRepository.findLowStockByRestaurant(restaurantId, effectiveThreshold)
                .stream()
                .map(menuItemMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public MenuItemResponse createMenuItem(MenuItemRequest request) {
        MenuCategory category = menuCategoryRepository.findByIdWithRestaurant(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        verifyOwnership(category.getRestaurant());

        MenuItem menuItem = new MenuItem();
        menuItem.setCategory(category);
        menuItem.setAvailable(true);
        mapRequestToMenuItem(request, menuItem);

        menuItem = menuItemRepository.save(menuItem);
        stockReservationService.syncStock(menuItem);
        invalidateMenuCaches(category.getRestaurant().getId());
        autocompleteService.indexMenuItem(menuItem.getId(), menuItem.getName());

        return menuItemMapper.resolveImageUrls(menuItem, menuItemMapper.toResponse(menuItem));
    }

    @Override
    @Transactional
    public MenuItemResponse updateMenuItem(Long id, MenuItemRequest request) {
        // Use JOIN FETCH to load category and restaurant
        MenuItem menuItem = menuItemRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        verifyOwnership(menuItem.getCategory().getRestaurant());

        mapRequestToMenuItem(request, menuItem);

        menuItem = menuItemRepository.save(menuItem);
        stockReservationService.syncStock(menuItem);

        cacheService.delete(CacheKeyGenerator.menuItem(id));
        invalidateMenuCaches(menuItem.getCategory().getRestaurant().getId());
        autocompleteService.indexMenuItem(menuItem.getId(), menuItem.getName());

        return menuItemMapper.resolveImageUrls(menuItem, menuItemMapper.toResponse(menuItem));
    }

@Override
    @Transactional
    public void deleteMenuItem(Long id) {
        MenuItem menuItem = menuItemRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        Long restaurantId = menuItem.getCategory().getRestaurant().getId();
        verifyOwnership(menuItem.getCategory().getRestaurant());

        long orderItemCount = orderItemRepository.countByMenuItemId(id);
        if (orderItemCount > 0) {
            throw new BusinessException("Cannot delete menu item because it is used in existing orders");
        }

        try {
            menuItemRepository.delete(menuItem);
        } catch (DataAccessException e) {
            throw new BusinessException("Cannot delete menu item due to dependencies: " + e.getMostSpecificCause().getMessage());
        }

        cacheService.delete(CacheKeyGenerator.menuItem(id));
        invalidateMenuCaches(restaurantId);
    }

    @Override
    @Transactional
    public void toggleItemAvailability(Long itemId, Boolean available) {
        MenuItem menuItem = menuItemRepository.findByIdWithDetails(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        if (menuItem.getCategory() == null) {
            throw new ResourceNotFoundException("Menu item category not found");
        }

        Restaurant restaurant = menuItem.getCategory().getRestaurant();
        if (restaurant == null) {
            throw new ResourceNotFoundException("Restaurant not found for menu item");
        }

        verifyOwnership(restaurant);

        menuItem.setAvailable(available);
        menuItemRepository.save(menuItem);

        cacheService.delete(CacheKeyGenerator.menuItem(itemId));
        invalidateMenuCaches(restaurant.getId());
    }

    @Override
    @Transactional
    public MenuImageUploadResponse createMenuItemImageUploadUrl(Long menuItemId, MenuImageUploadRequest request) {
        MenuItem menuItem = menuItemRepository.findByIdWithDetails(menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
        Restaurant restaurant = menuItem.getCategory().getRestaurant();
        verifyOwnership(restaurant);

        String imageKey = menuImageService.generateImageKey(restaurant.getId(), menuItemId, request.getContentType());
        String uploadUrl = menuImageService.createUploadUrl(imageKey, request.getContentType());

        return MenuImageUploadResponse.builder()
                .uploadUrl(uploadUrl)
                .imageKey(imageKey)
                .expiresInSeconds(imageStorageProperties.getUploadUrlExpirySeconds())
                .build();
    }

    // ==================== HELPERS ====================

    private void verifyOwnership(Restaurant restaurant) {
        if (restaurant.getOwner() == null || !restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("You don't own this restaurant");
        }
    }

    private void invalidateMenuCaches(Long restaurantId) {
        cacheService.deletePattern("menu-item");
        cacheService.deletePattern("menu-search");
        cacheService.deletePattern("bestseller");
        cacheService.deletePattern("recommended");
        cacheService.deletePattern("menu-category");
    }

    private void mapRequestToMenuItem(MenuItemRequest request, MenuItem menuItem) {
        if (request.getName() != null) menuItem.setName(request.getName());
        if (request.getDescription() != null) menuItem.setDescription(request.getDescription());
        if (request.getPrice() != null) menuItem.setPrice(request.getPrice());
        if (request.getOriginalPrice() != null) menuItem.setOriginalPrice(request.getOriginalPrice());
        if (request.getFoodType() != null) menuItem.setFoodType(request.getFoodType());
        if (request.getIsVeg() != null) menuItem.setIsVeg(request.getIsVeg());
        if (request.getIsSpicy() != null) menuItem.setIsSpicy(request.getIsSpicy());
        if (request.getSpiceLevel() != null) menuItem.setSpiceLevel(request.getSpiceLevel());
        if (request.getImageKey() != null) {
            menuImageService.validateImageKey(request.getImageKey());
            menuItem.setImageUrl(request.getImageKey());
        } else if (request.getImageUrl() != null) {
            if (imageStorageProperties.isEnabled()) {
                throw new com.bhukkad.exception.BusinessException(
                        "Use image upload-url flow instead of raw image URLs when S3 is enabled");
            }
            menuItem.setImageUrl(request.getImageUrl());
        }
        if (request.getPreparationTime() != null) menuItem.setPreparationTime(request.getPreparationTime());
        if (request.getCalories() != null) menuItem.setCalories(request.getCalories());
        if (request.getServingSize() != null) menuItem.setServingSize(request.getServingSize());
        if (request.getStockQuantity() != null) menuItem.setStockQuantity(request.getStockQuantity());

        // ElementCollections - replace entirely
        if (request.getIngredients() != null) {
            menuItem.getIngredients().clear();
            menuItem.getIngredients().addAll(request.getIngredients());
        }

        if (request.getTags() != null) {
            menuItem.getTags().clear();
            menuItem.getTags().addAll(request.getTags());
        }

        if (request.getAllergens() != null) {
            menuItem.getAllergens().clear();
            menuItem.getAllergens().addAll(request.getAllergens());
        }

        if (request.getAdditionalImages() != null) {
            menuItem.getAdditionalImages().clear();
            menuItem.getAdditionalImages().addAll(request.getAdditionalImages());
        }

        // Discount
        if (request.getOriginalPrice() != null && request.getPrice() != null
                && request.getOriginalPrice() > request.getPrice()) {
            double discount = ((request.getOriginalPrice() - request.getPrice()) / request.getOriginalPrice()) * 100;
            menuItem.setDiscountPercentage(Math.round(discount * 100.0) / 100.0);
        }
    }

    private MenuCategoryResponse mapToCategoryResponse(MenuCategory category) {
        int itemCount = 0;
        try {
            itemCount = menuItemRepository.countByCategoryId(category.getId());
        } catch (Exception e) {
            log.debug("Could not count items for category: {}", category.getId());
        }

        return MenuCategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .restaurantId(category.getRestaurant().getId())
                .displayOrder(category.getDisplayOrder())
                .active(category.getActive())
                .itemCount(itemCount)
                .build();
    }

    // ==================== DIETARY FILTERING ====================

    @Override
    @UseReadReplica
    public List<MenuItemResponse> filterMenuItemsByDiet(Long restaurantId, MenuItem.FoodType foodType, Set<String> excludeAllergens, MenuItem.SpiceLevel maxSpiceLevel) {
        String cacheKey = CacheKeyGenerator.menuItemsByDiet(restaurantId, foodType, excludeAllergens, maxSpiceLevel);
        return cacheService.getListOrCompute(cacheKey, MenuItemResponse.class, searchTtl, () -> {
            List<MenuItem> items = menuItemRepository.findByRestaurantIdWithDetails(restaurantId);
            return items.stream()
                    .filter(item -> foodType == null || item.getFoodType() == foodType)
                    .filter(item -> excludeAllergens == null || excludeAllergens.isEmpty() || 
                            item.getAllergens() == null || 
                            item.getAllergens().stream().noneMatch(excludeAllergens::contains))
                    .filter(item -> maxSpiceLevel == null || item.getSpiceLevel() == null || 
                            item.getSpiceLevel().ordinal() <= maxSpiceLevel.ordinal())
                    .map(menuItemMapper::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    @UseReadReplica
    public List<MenuItemResponse> getVeganItems(Long restaurantId) {
        return filterMenuItemsByDiet(restaurantId, MenuItem.FoodType.VEGAN, null, null);
    }

    @Override
    @UseReadReplica
    public List<MenuItemResponse> getVegetarianItems(Long restaurantId) {
        return filterMenuItemsByDiet(restaurantId, MenuItem.FoodType.VEG, null, null);
    }

    @Override
    @UseReadReplica
    public List<MenuItemResponse> getGlutenFreeItems(Long restaurantId) {
        return filterMenuItemsByDiet(restaurantId, null, Set.of("gluten", "wheat"), null);
    }
}
