package com.bhukkad.serviceImpl;

import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.dto.request.MenuCategoryRequest;
import com.bhukkad.dto.request.MenuItemRequest;
import com.bhukkad.dto.response.MenuCategoryResponse;
import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.MenuCategoryRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MenuServiceImpl implements MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuServiceImpl.class);

    private final MenuItemRepository menuItemRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final RestaurantRepository restaurantRepository;
    private final SecurityUtils securityUtils;
    private final RedisCacheService cacheService;

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
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
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
    @Transactional(readOnly = true)
    public List<MenuCategoryResponse> getCategoriesByRestaurant(Long restaurantId) {
        String cacheKey = CacheKeyGenerator.menuCategoriesByRestaurant(restaurantId);

        Optional<List<MenuCategoryResponse>> cached = cacheService.getList(cacheKey, MenuCategoryResponse.class);
        if (cached.isPresent()) return cached.get();

        List<MenuCategoryResponse> categories = menuCategoryRepository
                .findByRestaurantIdOrderByDisplayOrderAsc(restaurantId)
                .stream()
                .map(this::mapToCategoryResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, categories, menuCategoryTtl);
        return categories;
    }

    @Override
    @Transactional
    public MenuCategoryResponse updateCategory(Long categoryId, MenuCategoryRequest request) {
        MenuCategory category = menuCategoryRepository.findById(categoryId)
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
        MenuCategory category = menuCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        verifyOwnership(category.getRestaurant());
        menuCategoryRepository.delete(category);
        cacheService.deletePattern("menu-category");
        cacheService.deletePattern("menu-item");
    }

    // ==================== MENU ITEMS ====================

    @Override
    @Transactional(readOnly = true)
    public MenuItemResponse getMenuItemById(Long id) {
        String cacheKey = CacheKeyGenerator.menuItem(id);

        Optional<MenuItemResponse> cached = cacheService.get(cacheKey, MenuItemResponse.class);
        if (cached.isPresent()) return cached.get();

        MenuItem menuItem = menuItemRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        MenuItemResponse response = mapToMenuItemResponse(menuItem);
        cacheService.set(cacheKey, response, menuItemTtl);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuItemResponse> getMenuItemsByRestaurant(Long restaurantId) {
        String cacheKey = CacheKeyGenerator.menuItemsByRestaurant(restaurantId);

        Optional<List<MenuItemResponse>> cached = cacheService.getList(cacheKey, MenuItemResponse.class);
        if (cached.isPresent()) return cached.get();

        List<MenuItemResponse> items = menuItemRepository
                .findByRestaurantIdWithDetails(restaurantId)
                .stream()
                .map(this::mapToMenuItemResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, items, menuItemTtl);
        return items;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuItemResponse> getMenuItemsByCategory(Long categoryId) {
        String cacheKey = CacheKeyGenerator.menuItemsByCategory(categoryId);

        Optional<List<MenuItemResponse>> cached = cacheService.getList(cacheKey, MenuItemResponse.class);
        if (cached.isPresent()) return cached.get();

        List<MenuItemResponse> items = menuItemRepository
                .findByCategoryIdWithDetails(categoryId)
                .stream()
                .map(this::mapToMenuItemResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, items, menuItemTtl);
        return items;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuItemResponse> getBestsellers(Long restaurantId) {
        String cacheKey = CacheKeyGenerator.bestsellers(restaurantId);

        Optional<List<MenuItemResponse>> cached = cacheService.getList(cacheKey, MenuItemResponse.class);
        if (cached.isPresent()) return cached.get();

        List<MenuItemResponse> items = menuItemRepository
                .findBestsellersWithDetails(restaurantId)
                .stream()
                .map(this::mapToMenuItemResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, items, menuItemTtl);
        return items;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuItemResponse> getRecommended(Long restaurantId) {
        return getMenuItemsByRestaurant(restaurantId).stream()
                .filter(item -> Boolean.TRUE.equals(item.getRecommended()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuItemResponse> searchMenuItems(String keyword) {
        String cacheKey = "menu-search:" + keyword.toLowerCase().trim();

        Optional<List<MenuItemResponse>> cached = cacheService.getList(cacheKey, MenuItemResponse.class);
        if (cached.isPresent()) return cached.get();

        List<MenuItemResponse> items = menuItemRepository
                .searchByNameWithDetails(keyword)
                .stream()
                .map(this::mapToMenuItemResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, items, searchTtl);
        return items;
    }

    @Override
    @Transactional
    public MenuItemResponse createMenuItem(MenuItemRequest request) {
        MenuCategory category = menuCategoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        verifyOwnership(category.getRestaurant());

        MenuItem menuItem = new MenuItem();
        menuItem.setCategory(category);
        mapRequestToMenuItem(request, menuItem);

        menuItem = menuItemRepository.save(menuItem);
        invalidateMenuCaches(category.getRestaurant().getId());

        return mapToMenuItemResponse(menuItem);
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

        cacheService.delete(CacheKeyGenerator.menuItem(id));
        invalidateMenuCaches(menuItem.getCategory().getRestaurant().getId());

        return mapToMenuItemResponse(menuItem);
    }

    @Override
    @Transactional
    public void deleteMenuItem(Long id) {
        MenuItem menuItem = menuItemRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        Long restaurantId = menuItem.getCategory().getRestaurant().getId();
        verifyOwnership(menuItem.getCategory().getRestaurant());

        menuItemRepository.delete(menuItem);

        cacheService.delete(CacheKeyGenerator.menuItem(id));
        invalidateMenuCaches(restaurantId);
    }

    @Override
    @Transactional
    public void toggleItemAvailability(Long itemId, Boolean available) {
        MenuItem menuItem = menuItemRepository.findByIdWithDetails(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        verifyOwnership(menuItem.getCategory().getRestaurant());

        menuItem.setAvailable(available);
        menuItemRepository.save(menuItem);

        cacheService.delete(CacheKeyGenerator.menuItem(itemId));
        invalidateMenuCaches(menuItem.getCategory().getRestaurant().getId());
    }

    // ==================== HELPERS ====================

    private void verifyOwnership(Restaurant restaurant) {
        try {
            Long currentUserId = securityUtils.getCurrentUserId();
            if (!restaurant.getOwner().getId().equals(currentUserId)) {
                throw new UnauthorizedException("You don't own this restaurant");
            }
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.debug("No auth context - public access");
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
        if (request.getImageUrl() != null) menuItem.setImageUrl(request.getImageUrl());
        if (request.getPreparationTime() != null) menuItem.setPreparationTime(request.getPreparationTime());
        if (request.getCalories() != null) menuItem.setCalories(request.getCalories());
        if (request.getServingSize() != null) menuItem.setServingSize(request.getServingSize());

        menuItem.setAvailable(true);

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

    @Transactional(readOnly = true)
    private MenuItemResponse mapToMenuItemResponse(MenuItem menuItem) {
        String categoryName = "";
        try {
            categoryName = menuItem.getCategory().getName();
        } catch (Exception e) {
            log.debug("Could not get category name for item: {}", menuItem.getId());
        }

        // Safe access to ElementCollections
        Set<String> tags = new HashSet<>();
        Set<String> allergens = new HashSet<>();
        Set<String> ingredients = new HashSet<>();

        try { if (menuItem.getTags() != null) tags = new HashSet<>(menuItem.getTags()); } catch (Exception e) {}
        try { if (menuItem.getAllergens() != null) allergens = new HashSet<>(menuItem.getAllergens()); } catch (Exception e) {}
        try { if (menuItem.getIngredients() != null) ingredients = new HashSet<>(menuItem.getIngredients()); } catch (Exception e) {}

        return MenuItemResponse.builder()
                .id(menuItem.getId())
                .name(menuItem.getName())
                .description(menuItem.getDescription())
                .categoryName(categoryName)
                .price(menuItem.getPrice())
                .originalPrice(menuItem.getOriginalPrice())
                .discountPercentage(menuItem.getDiscountPercentage())
                .available(menuItem.getAvailable())
                .foodType(menuItem.getFoodType() != null ? menuItem.getFoodType().name() : null)
                .isVeg(menuItem.getIsVeg())
                .isSpicy(menuItem.getIsSpicy())
                .spiceLevel(menuItem.getSpiceLevel() != null ? menuItem.getSpiceLevel().name() : null)
                .imageUrl(menuItem.getImageUrl())
                .preparationTime(menuItem.getPreparationTime())
                .bestseller(menuItem.getBestseller())
                .recommended(menuItem.getRecommended())
                .calories(menuItem.getCalories())
                .servingSize(menuItem.getServingSize())
                .averageRating(menuItem.getAverageRating())
                .totalRatings(menuItem.getTotalRatings())
                .tags(tags)
                .allergens(allergens)
                .ingredients(ingredients)
                .build();
    }
}