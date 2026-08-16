package com.bhukkad.serviceImpl;

import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.config.InventoryProperties;
import com.bhukkad.config.LocalCacheProperties;
import com.bhukkad.inventory.StockReservationService;
import com.bhukkad.mapper.MenuItemMapper;
import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.repository.MenuCategoryRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.MenuService;
import com.bhukkad.storage.ImageStorageProperties;
import com.bhukkad.storage.MenuImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MenuServiceImplDietaryTest {

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private MenuCategoryRepository menuCategoryRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private RedisCacheService cacheService;

    @Mock
    private MenuItemMapper menuItemMapper;

    @Mock
    private InventoryProperties inventoryProperties;

    @Mock
    private StockReservationService stockReservationService;

    @InjectMocks
    private MenuServiceImpl menuService;

    @Test
    void filterMenuItemsByDiet_vegan_returnsOnlyVegan() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);

        MenuCategory category = new MenuCategory();
        category.setId(1L);
        category.setRestaurant(restaurant);

        MenuItem veganItem = new MenuItem();
        veganItem.setId(1L);
        veganItem.setName("Vegan Salad");
        veganItem.setFoodType(MenuItem.FoodType.VEGAN);
        veganItem.setAllergens(Set.of());
        veganItem.setAvailable(true);
        veganItem.setCategory(category);

        MenuItem vegItem = new MenuItem();
        vegItem.setId(2L);
        vegItem.setName("Paneer Tikka");
        vegItem.setFoodType(MenuItem.FoodType.VEG);
        vegItem.setAllergens(Set.of());
        vegItem.setAvailable(true);
        vegItem.setCategory(category);

        when(menuItemRepository.findByRestaurantIdWithDetails(1L))
                .thenReturn(List.of(veganItem, vegItem));
        when(menuItemMapper.toResponse(veganItem)).thenReturn(new MenuItemResponse());
        when(cacheService.getListOrCompute(any(String.class), any(Class.class), anyLong(), any()))
                .thenAnswer(inv -> {
                    Supplier<List<MenuItemResponse>> supplier = inv.getArgument(3);
                    return supplier.get();
                });

        var result = menuService.filterMenuItemsByDiet(1L, MenuItem.FoodType.VEGAN, null, null);

        assertEquals(1, result.size());
    }

    @Test
    void filterMenuItemsByDiet_excludeAllergens_filtersCorrectly() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);

        MenuCategory category = new MenuCategory();
        category.setId(1L);
        category.setRestaurant(restaurant);

        MenuItem safeItem = new MenuItem();
        safeItem.setId(1L);
        safeItem.setName("Safe Item");
        safeItem.setAllergens(Set.of("dairy"));
        safeItem.setAvailable(true);
        safeItem.setCategory(category);

        MenuItem allergenItem = new MenuItem();
        allergenItem.setId(2L);
        allergenItem.setName("Allergen Item");
        allergenItem.setAllergens(Set.of("nuts", "dairy"));
        allergenItem.setAvailable(true);
        allergenItem.setCategory(category);

        when(menuItemRepository.findByRestaurantIdWithDetails(1L))
                .thenReturn(List.of(safeItem, allergenItem));
        when(menuItemMapper.toResponse(any())).thenReturn(new MenuItemResponse());
        when(cacheService.getListOrCompute(any(String.class), any(Class.class), anyLong(), any()))
                .thenAnswer(inv -> {
                    Supplier<List<MenuItemResponse>> supplier = inv.getArgument(3);
                    return supplier.get();
                });

        var result = menuService.filterMenuItemsByDiet(1L, null, Set.of("nuts"), null);

        assertEquals(1, result.size());
    }

    @Test
    void filterMenuItemsByDiet_maxSpiceLevel_filtersCorrectly() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);

        MenuCategory category = new MenuCategory();
        category.setId(1L);
        category.setRestaurant(restaurant);

        MenuItem mildItem = new MenuItem();
        mildItem.setId(1L);
        mildItem.setName("Mild Curry");
        mildItem.setSpiceLevel(MenuItem.SpiceLevel.MILD);
        mildItem.setAvailable(true);
        mildItem.setCategory(category);

        MenuItem hotItem = new MenuItem();
        hotItem.setId(2L);
        hotItem.setName("Hot Curry");
        hotItem.setSpiceLevel(MenuItem.SpiceLevel.HOT);
        hotItem.setAvailable(true);
        hotItem.setCategory(category);

        when(menuItemRepository.findByRestaurantIdWithDetails(1L))
                .thenReturn(List.of(mildItem, hotItem));
        when(menuItemMapper.toResponse(any())).thenReturn(new MenuItemResponse());
        when(cacheService.getListOrCompute(any(String.class), any(Class.class), anyLong(), any()))
                .thenAnswer(inv -> {
                    Supplier<List<MenuItemResponse>> supplier = inv.getArgument(3);
                    return supplier.get();
                });

        var result = menuService.filterMenuItemsByDiet(1L, null, null, MenuItem.SpiceLevel.MEDIUM);

        assertEquals(1, result.size());
    }

    @Test
    void getVeganItems_returnsOnlyVegan() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);

        MenuCategory category = new MenuCategory();
        category.setId(1L);
        category.setRestaurant(restaurant);

        MenuItem veganItem = new MenuItem();
        veganItem.setId(1L);
        veganItem.setFoodType(MenuItem.FoodType.VEGAN);
        veganItem.setAllergens(Set.of());
        veganItem.setAvailable(true);
        veganItem.setCategory(category);

        when(menuItemRepository.findByRestaurantIdWithDetails(1L))
                .thenReturn(List.of(veganItem));
        when(menuItemMapper.toResponse(any())).thenReturn(new MenuItemResponse());
        when(cacheService.getListOrCompute(any(String.class), any(Class.class), anyLong(), any()))
                .thenAnswer(inv -> {
                    Supplier<List<MenuItemResponse>> supplier = inv.getArgument(3);
                    return supplier.get();
                });

        var result = menuService.getVeganItems(1L);

        assertEquals(1, result.size());
    }

    @Test
    void getVegetarianItems_returnsOnlyVegetarian() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);

        MenuCategory category = new MenuCategory();
        category.setId(1L);
        category.setRestaurant(restaurant);

        MenuItem vegItem = new MenuItem();
        vegItem.setId(1L);
        vegItem.setFoodType(MenuItem.FoodType.VEG);
        vegItem.setAvailable(true);
        vegItem.setCategory(category);

        when(menuItemRepository.findByRestaurantIdWithDetails(1L))
                .thenReturn(List.of(vegItem));
        when(menuItemMapper.toResponse(any())).thenReturn(new MenuItemResponse());
        when(cacheService.getListOrCompute(any(String.class), any(Class.class), anyLong(), any()))
                .thenAnswer(inv -> {
                    Supplier<List<MenuItemResponse>> supplier = inv.getArgument(3);
                    return supplier.get();
                });

        var result = menuService.getVegetarianItems(1L);

        assertEquals(1, result.size());
    }

    @Test
    void getGlutenFreeItems_excludesGlutenAndWheat() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);

        MenuCategory category = new MenuCategory();
        category.setId(1L);
        category.setRestaurant(restaurant);

        MenuItem safeItem = new MenuItem();
        safeItem.setId(1L);
        safeItem.setAllergens(Set.of("dairy"));
        safeItem.setAvailable(true);
        safeItem.setCategory(category);

        MenuItem glutenItem = new MenuItem();
        glutenItem.setId(2L);
        glutenItem.setAllergens(Set.of("gluten"));
        glutenItem.setAvailable(true);
        glutenItem.setCategory(category);

        when(menuItemRepository.findByRestaurantIdWithDetails(1L))
                .thenReturn(List.of(safeItem, glutenItem));
        when(menuItemMapper.toResponse(any())).thenReturn(new MenuItemResponse());
        when(cacheService.getListOrCompute(any(String.class), any(Class.class), anyLong(), any()))
                .thenAnswer(inv -> {
                    Supplier<List<MenuItemResponse>> supplier = inv.getArgument(3);
                    return supplier.get();
                });

        var result = menuService.getGlutenFreeItems(1L);

        assertEquals(1, result.size());
    }
}