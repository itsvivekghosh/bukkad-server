package com.bhukkad.serviceImpl;

import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.dto.request.MenuItemRequest;
import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
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

import java.util.List;
import java.util.Optional;
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

    @Override
    public MenuItemResponse getMenuItemById(Long id) {
        String cacheKey = CacheKeyGenerator.menuItem(id);

        Optional<MenuItemResponse> cached = cacheService.get(cacheKey, MenuItemResponse.class);
        if (cached.isPresent()) return cached.get();

        MenuItem menuItem = menuItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        MenuItemResponse response = mapToMenuItemResponse(menuItem);
        cacheService.set(cacheKey, response, menuItemTtl);
        return response;
    }

    @Override
    public List<MenuItemResponse> getMenuItemsByRestaurant(Long restaurantId) {
        String cacheKey = CacheKeyGenerator.menuItemsByRestaurant(restaurantId);

        Optional<List<MenuItemResponse>> cached = cacheService.getList(cacheKey, MenuItemResponse.class);
        if (cached.isPresent()) return cached.get();

        List<MenuItemResponse> items = menuItemRepository
                .findByCategoryRestaurantIdAndAvailableTrue(restaurantId).stream()
                .map(this::mapToMenuItemResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, items, menuItemTtl);
        return items;
    }

    @Override
    public List<MenuItemResponse> getMenuItemsByCategory(Long categoryId) {
        String cacheKey = CacheKeyGenerator.menuItemsByCategory(categoryId);

        Optional<List<MenuItemResponse>> cached = cacheService.getList(cacheKey, MenuItemResponse.class);
        if (cached.isPresent()) return cached.get();

        List<MenuItemResponse> items = menuItemRepository
                .findByCategoryIdAndAvailableTrue(categoryId).stream()
                .map(this::mapToMenuItemResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, items, menuItemTtl);
        return items;
    }

    @Override
    public List<MenuItemResponse> getBestsellers(Long restaurantId) {
        String cacheKey = CacheKeyGenerator.bestsellers(restaurantId);

        Optional<List<MenuItemResponse>> cached = cacheService.getList(cacheKey, MenuItemResponse.class);
        if (cached.isPresent()) return cached.get();

        List<MenuItemResponse> items = menuItemRepository
                .findBestsellersByRestaurant(restaurantId).stream()
                .map(this::mapToMenuItemResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, items, menuItemTtl);
        return items;
    }

    @Override
    public List<MenuItemResponse> searchMenuItems(String keyword) {
        String cacheKey = "menu-search:" + keyword.toLowerCase().trim();

        Optional<List<MenuItemResponse>> cached = cacheService.getList(cacheKey, MenuItemResponse.class);
        if (cached.isPresent()) return cached.get();

        List<MenuItemResponse> items = menuItemRepository.searchByName(keyword).stream()
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

        if (!category.getRestaurant().getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Not your restaurant");
        }

        MenuItem menuItem = new MenuItem();
        menuItem.setName(request.getName());
        menuItem.setDescription(request.getDescription());
        menuItem.setCategory(category);
        menuItem.setPrice(request.getPrice());
        menuItem.setOriginalPrice(request.getOriginalPrice());
        menuItem.setFoodType(request.getFoodType());
        menuItem.setIsVeg(request.getIsVeg());
        menuItem.setAvailable(true);

        menuItem = menuItemRepository.save(menuItem);
        MenuItemResponse response = mapToMenuItemResponse(menuItem);

        // Invalidate menu caches for this restaurant
        invalidateMenuCaches(category.getRestaurant().getId());

        return response;
    }

    @Override
    @Transactional
    public MenuItemResponse updateMenuItem(Long id, MenuItemRequest request) {
        MenuItem menuItem = menuItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        menuItem.setName(request.getName());
        menuItem.setPrice(request.getPrice());
        menuItem = menuItemRepository.save(menuItem);

        MenuItemResponse response = mapToMenuItemResponse(menuItem);

        // Invalidate
        cacheService.delete(CacheKeyGenerator.menuItem(id));
        invalidateMenuCaches(menuItem.getCategory().getRestaurant().getId());

        return response;
    }

    @Override
    @Transactional
    public void deleteMenuItem(Long id) {
        MenuItem menuItem = menuItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        Long restaurantId = menuItem.getCategory().getRestaurant().getId();
        menuItemRepository.delete(menuItem);

        cacheService.delete(CacheKeyGenerator.menuItem(id));
        invalidateMenuCaches(restaurantId);
    }

    @Override
    @Transactional
    public void toggleItemAvailability(Long itemId, Boolean available) {
        MenuItem menuItem = menuItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        menuItem.setAvailable(available);
        menuItemRepository.save(menuItem);

        cacheService.delete(CacheKeyGenerator.menuItem(itemId));
        invalidateMenuCaches(menuItem.getCategory().getRestaurant().getId());
    }

    @Override
    public MenuCategory createCategory(Long restaurantId, MenuCategory category) {
        var restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        category.setRestaurant(restaurant);
        MenuCategory saved = menuCategoryRepository.save(category);
        cacheService.deletePattern("menu-category");
        return saved;
    }

    @Override
    public List<MenuCategory> getCategoriesByRestaurant(Long restaurantId) {
        String cacheKey = CacheKeyGenerator.menuCategoriesByRestaurant(restaurantId);

        Optional<List<MenuCategory>> cached = cacheService.getList(cacheKey, MenuCategory.class);
        if (cached.isPresent()) return cached.get();

        List<MenuCategory> categories = menuCategoryRepository
                .findByRestaurantIdOrderByDisplayOrderAsc(restaurantId);

        cacheService.set(cacheKey, categories, menuCategoryTtl);
        return categories;
    }

    @Override
    public MenuCategory updateCategory(Long categoryId, MenuCategory category) {
        MenuCategory existing = menuCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        existing.setName(category.getName());
        existing.setDescription(category.getDescription());
        MenuCategory saved = menuCategoryRepository.save(existing);
        cacheService.deletePattern("menu-category");
        return saved;
    }

    @Override
    public void deleteCategory(Long categoryId) {
        menuCategoryRepository.deleteById(categoryId);
        cacheService.deletePattern("menu-category");
    }

    @Override
    public List<MenuItemResponse> getRecommended(Long restaurantId) {
        return getMenuItemsByRestaurant(restaurantId).stream()
                .filter(item -> Boolean.TRUE.equals(item.getRecommended()))
                .collect(Collectors.toList());
    }

    private void invalidateMenuCaches(Long restaurantId) {
        cacheService.deletePattern("menu-item");
        cacheService.deletePattern("menu-search");
        cacheService.deletePattern("bestseller");
        cacheService.deletePattern("recommended");
        log.debug("CACHE_INVALIDATED menu caches for restaurant={}", restaurantId);
    }

    private MenuItemResponse mapToMenuItemResponse(MenuItem menuItem) {
        return MenuItemResponse.builder()
                .id(menuItem.getId())
                .name(menuItem.getName())
                .description(menuItem.getDescription())
                .categoryName(menuItem.getCategory().getName())
                .price(menuItem.getPrice())
                .originalPrice(menuItem.getOriginalPrice())
                .discountPercentage(menuItem.getDiscountPercentage())
                .available(menuItem.getAvailable())
                .foodType(menuItem.getFoodType().name())
                .isVeg(menuItem.getIsVeg())
                .isSpicy(menuItem.getIsSpicy())
                .preparationTime(menuItem.getPreparationTime())
                .bestseller(menuItem.getBestseller())
                .recommended(menuItem.getRecommended())
                .calories(menuItem.getCalories())
                .averageRating(menuItem.getAverageRating())
                .totalRatings(menuItem.getTotalRatings())
                .tags(menuItem.getTags())
                .build();
    }
}