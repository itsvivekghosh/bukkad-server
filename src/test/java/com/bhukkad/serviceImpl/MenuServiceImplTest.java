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
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.mapper.MenuItemMapper;
import com.bhukkad.storage.ImageStorageProperties;
import com.bhukkad.storage.MenuImageService;
import com.bhukkad.repository.MenuCategoryRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuServiceImplTest {

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
    private MenuImageService menuImageService;
    @Mock
    private ImageStorageProperties imageStorageProperties;

    @InjectMocks
    private MenuServiceImpl menuService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(menuService, "menuItemTtl", 900L);
        ReflectionTestUtils.setField(menuService, "menuCategoryTtl", 1800L);
        ReflectionTestUtils.setField(menuService, "searchTtl", 300L);

        lenient().when(imageStorageProperties.isEnabled()).thenReturn(false);
        lenient().when(menuItemMapper.toResponse(any(MenuItem.class))).thenAnswer(invocation -> {
            MenuItem item = invocation.getArgument(0);
            String categoryName = "";
            try {
                if (item.getCategory() != null) {
                    categoryName = item.getCategory().getName();
                }
            } catch (Exception ignored) {
                categoryName = "";
            }
            Set<String> tags = new HashSet<>();
            Set<String> allergens = new HashSet<>();
            Set<String> ingredients = new HashSet<>();
            try {
                if (item.getTags() != null) {
                    tags = new HashSet<>(item.getTags());
                }
            } catch (Exception ignored) {
            }
            try {
                if (item.getAllergens() != null) {
                    allergens = new HashSet<>(item.getAllergens());
                }
            } catch (Exception ignored) {
            }
            try {
                if (item.getIngredients() != null) {
                    ingredients = new HashSet<>(item.getIngredients());
                }
            } catch (Exception ignored) {
            }
            return MenuItemResponse.builder()
                    .id(item.getId())
                    .name(item.getName())
                    .description(item.getDescription())
                    .categoryName(categoryName)
                    .price(item.getPrice())
                    .originalPrice(item.getOriginalPrice())
                    .discountPercentage(item.getDiscountPercentage())
                    .available(item.getAvailable())
                    .foodType(item.getFoodType() != null ? item.getFoodType().name() : null)
                    .isVeg(item.getIsVeg())
                    .isSpicy(item.getIsSpicy())
                    .spiceLevel(item.getSpiceLevel() != null ? item.getSpiceLevel().name() : null)
                    .imageUrl(item.getImageUrl())
                    .preparationTime(item.getPreparationTime())
                    .bestseller(item.getBestseller())
                    .recommended(item.getRecommended())
                    .calories(item.getCalories())
                    .servingSize(item.getServingSize())
                    .averageRating(item.getAverageRating())
                    .totalRatings(item.getTotalRatings())
                    .tags(tags)
                    .allergens(allergens)
                    .ingredients(ingredients)
                    .build();
        });

        lenient().when(cacheService.getListOrCompute(anyString(), any(Class.class), anyLong(), any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    Class<?> type = invocation.getArgument(1);
                    long ttl = invocation.getArgument(2);
                    Supplier<?> supplier = invocation.getArgument(3);
                    Optional<?> cached = cacheService.getList(key, type);
                    if (cached.isPresent()) {
                        return cached.get();
                    }
                    Object value = supplier.get();
                    cacheService.set(key, value, ttl);
                    return value;
                });

        lenient().when(cacheService.getOrCompute(anyString(), any(Class.class), anyLong(), any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    Class<?> type = invocation.getArgument(1);
                    long ttl = invocation.getArgument(2);
                    Supplier<?> supplier = invocation.getArgument(3);
                    Optional<?> cached = cacheService.get(key, type);
                    if (cached.isPresent()) {
                        return cached.get();
                    }
                    Object value = supplier.get();
                    cacheService.set(key, value, ttl);
                    return value;
                });
    }

    @Test
    void createCategory_restaurantNotFound_throws() {
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> menuService.createCategory(1L, categoryRequest("Starters", "desc", 1, true)));
        assertEquals("Restaurant not found", ex.getMessage());
    }

    @Test
    void createCategory_notOwner_throws() {
        Restaurant restaurant = restaurant(1L, 9L);
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(8L);

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> menuService.createCategory(1L, categoryRequest("Starters", "desc", 1, true)));
        assertEquals("You don't own this restaurant", ex.getMessage());
    }

    @Test
    void createCategory_defaultsDisplayOrderAndActive() {
        Restaurant restaurant = restaurant(1L, 9L);
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(menuCategoryRepository.save(any(MenuCategory.class))).thenAnswer(inv -> {
            MenuCategory saved = inv.getArgument(0);
            saved.setId(3L);
            return saved;
        });
        when(menuItemRepository.countByCategoryId(3L)).thenReturn(4);

        MenuCategoryResponse response = menuService.createCategory(1L, categoryRequest("Starters", "desc", null, null));

        ArgumentCaptor<MenuCategory> captor = ArgumentCaptor.forClass(MenuCategory.class);
        verify(menuCategoryRepository).save(captor.capture());
        assertEquals(0, captor.getValue().getDisplayOrder());
        assertTrue(captor.getValue().getActive());
        assertEquals(3L, response.getId());
        assertEquals("Starters", response.getName());
        assertEquals("desc", response.getDescription());
        assertEquals(1L, response.getRestaurantId());
        assertEquals(4, response.getItemCount());
        verify(cacheService).deletePattern("menu-category");
    }

    @Test
    void createCategory_noAuth_throws() {
        Restaurant restaurant = restaurant(1L, 9L);
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenThrow(new RuntimeException("no auth"));

        assertThrows(RuntimeException.class,
                () -> menuService.createCategory(1L, categoryRequest("Mains", "hot", 2, false)));
        verify(menuCategoryRepository, never()).save(any());
    }

    @Test
    void getCategoriesByRestaurant_cacheHit() {
        List<MenuCategoryResponse> cached = List.of(MenuCategoryResponse.builder().id(1L).build());
        when(cacheService.getList(CacheKeyGenerator.menuCategoriesByRestaurant(1L), MenuCategoryResponse.class))
                .thenReturn(Optional.of(cached));

        assertSame(cached, menuService.getCategoriesByRestaurant(1L));
        verify(menuCategoryRepository, never()).findByRestaurantIdWithRestaurantOrderByDisplayOrderAsc(anyLong());
    }

    @Test
    void getCategoriesByRestaurant_cacheMiss_loadsAndCaches() {
        when(cacheService.getList(CacheKeyGenerator.menuCategoriesByRestaurant(1L), MenuCategoryResponse.class))
                .thenReturn(Optional.empty());
        MenuCategory category = category(2L, "Starters", restaurant(1L, 9L));
        when(menuCategoryRepository.findByRestaurantIdWithRestaurantOrderByDisplayOrderAsc(1L)).thenReturn(List.of(category));
        when(menuItemRepository.countByCategoryId(2L)).thenThrow(new RuntimeException("count failed"));

        List<MenuCategoryResponse> result = menuService.getCategoriesByRestaurant(1L);

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).getItemCount());
        verify(cacheService).set(eq(CacheKeyGenerator.menuCategoriesByRestaurant(1L)), eq(result), eq(1800L));
    }

    @Test
    void updateCategory_notFound_throws() {
        when(menuCategoryRepository.findByIdWithRestaurant(2L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> menuService.updateCategory(2L, categoryRequest("X", "Y", 1, true)));
    }

    @Test
    void updateCategory_updatesOnlyProvidedFields() {
        MenuCategory category = category(2L, "Old", restaurant(1L, 9L));
        category.setDescription("old desc");
        category.setDisplayOrder(1);
        category.setActive(true);
        when(menuCategoryRepository.findByIdWithRestaurant(2L)).thenReturn(Optional.of(category));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(menuCategoryRepository.save(category)).thenReturn(category);
        when(menuItemRepository.countByCategoryId(2L)).thenReturn(1);

        MenuCategoryRequest request = new MenuCategoryRequest();
        request.setActive(null);
        MenuCategoryResponse response = menuService.updateCategory(2L, request);

        assertEquals("Old", category.getName());
        assertEquals("old desc", category.getDescription());
        assertEquals(1, category.getDisplayOrder());
        assertTrue(category.getActive());
        assertEquals("Old", response.getName());
        verify(cacheService).deletePattern("menu-category");
    }

    @Test
    void updateCategory_updatesAllFields() {
        MenuCategory category = category(2L, "Old", restaurant(1L, 9L));
        when(menuCategoryRepository.findByIdWithRestaurant(2L)).thenReturn(Optional.of(category));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(menuCategoryRepository.save(category)).thenReturn(category);
        when(menuItemRepository.countByCategoryId(2L)).thenReturn(0);

        menuService.updateCategory(2L, categoryRequest("New", "new desc", 5, false));

        assertEquals("New", category.getName());
        assertEquals("new desc", category.getDescription());
        assertEquals(5, category.getDisplayOrder());
        assertEquals(false, category.getActive());
    }

    @Test
    void deleteCategory_notFound_throws() {
        when(menuCategoryRepository.findByIdWithRestaurant(2L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> menuService.deleteCategory(2L));
    }

    @Test
    void deleteCategory_deletesAndInvalidatesCaches() {
        MenuCategory category = category(2L, "Starters", restaurant(1L, 9L));
        when(menuCategoryRepository.findByIdWithRestaurant(2L)).thenReturn(Optional.of(category));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);

        menuService.deleteCategory(2L);

        verify(menuCategoryRepository).delete(category);
        verify(cacheService).deletePattern("menu-category");
        verify(cacheService).deletePattern("menu-item");
    }

    @Test
    void getMenuItemById_cacheHit() {
        MenuItemResponse cached = MenuItemResponse.builder().id(1L).build();
        when(cacheService.get(CacheKeyGenerator.menuItem(1L), MenuItemResponse.class))
                .thenReturn(Optional.of(cached));

        assertSame(cached, menuService.getMenuItemById(1L));
        verify(menuItemRepository, never()).findByIdWithDetails(anyLong());
    }

    @Test
    void getMenuItemById_notFound_throws() {
        when(cacheService.get(CacheKeyGenerator.menuItem(1L), MenuItemResponse.class))
                .thenReturn(Optional.empty());
        when(menuItemRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> menuService.getMenuItemById(1L));
        assertEquals("Menu item not found", ex.getMessage());
    }

    @Test
    void getMenuItemById_mapsAllFieldsAndCaches() {
        when(cacheService.get(CacheKeyGenerator.menuItem(1L), MenuItemResponse.class))
                .thenReturn(Optional.empty());
        MenuItem item = fullMenuItem(1L);
        when(menuItemRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(item));

        MenuItemResponse response = menuService.getMenuItemById(1L);

        assertEquals(1L, response.getId());
        assertEquals("Biryani", response.getName());
        assertEquals("Hyderabadi", response.getDescription());
        assertEquals("Mains", response.getCategoryName());
        assertEquals(250.0, response.getPrice());
        assertEquals(300.0, response.getOriginalPrice());
        assertEquals(16.67, response.getDiscountPercentage());
        assertEquals(true, response.getAvailable());
        assertEquals("NON_VEG", response.getFoodType());
        assertEquals(false, response.getIsVeg());
        assertEquals(true, response.getIsSpicy());
        assertEquals("HOT", response.getSpiceLevel());
        assertEquals("img.png", response.getImageUrl());
        assertEquals(20, response.getPreparationTime());
        assertEquals(true, response.getBestseller());
        assertEquals(true, response.getRecommended());
        assertEquals(600, response.getCalories());
        assertEquals("1 plate", response.getServingSize());
        assertEquals(4.5, response.getAverageRating());
        assertEquals(10, response.getTotalRatings());
        assertTrue(response.getTags().contains("popular"));
        assertTrue(response.getAllergens().contains("nuts"));
        assertTrue(response.getIngredients().contains("rice"));
        verify(cacheService).set(eq(CacheKeyGenerator.menuItem(1L)), any(MenuItemResponse.class), eq(900L));
    }

    @Test
    void getMenuItemById_nullEnumsAndCollectionFailures_mapSafely() {
        when(cacheService.get(CacheKeyGenerator.menuItem(2L), MenuItemResponse.class))
                .thenReturn(Optional.empty());
        MenuItem item = mock(MenuItem.class);
        when(item.getId()).thenReturn(2L);
        when(item.getName()).thenReturn("Plain Rice");
        when(item.getCategory()).thenThrow(new RuntimeException("lazy"));
        when(item.getFoodType()).thenReturn(null);
        when(item.getSpiceLevel()).thenReturn(null);
        when(item.getTags()).thenThrow(new RuntimeException("tags"));
        when(item.getAllergens()).thenThrow(new RuntimeException("allergens"));
        when(item.getIngredients()).thenThrow(new RuntimeException("ingredients"));
        when(menuItemRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(item));

        MenuItemResponse response = menuService.getMenuItemById(2L);

        assertEquals("", response.getCategoryName());
        assertNull(response.getFoodType());
        assertNull(response.getSpiceLevel());
        assertTrue(response.getTags().isEmpty());
        assertTrue(response.getAllergens().isEmpty());
        assertTrue(response.getIngredients().isEmpty());
    }

    @Test
    void getMenuItemById_nullCollections_mapToEmptySets() {
        when(cacheService.get(CacheKeyGenerator.menuItem(3L), MenuItemResponse.class))
                .thenReturn(Optional.empty());
        MenuItem item = new MenuItem();
        item.setId(3L);
        item.setName("Tea");
        item.setCategory(category(1L, "Drinks", restaurant(1L, 9L)));
        item.setTags(null);
        item.setAllergens(null);
        item.setIngredients(null);
        when(menuItemRepository.findByIdWithDetails(3L)).thenReturn(Optional.of(item));

        MenuItemResponse response = menuService.getMenuItemById(3L);

        assertTrue(response.getTags().isEmpty());
        assertTrue(response.getAllergens().isEmpty());
        assertTrue(response.getIngredients().isEmpty());
    }

    @Test
    void getMenuItemsByRestaurant_cacheHitAndMiss() {
        List<MenuItemResponse> cached = List.of(MenuItemResponse.builder().id(1L).build());
        when(cacheService.getList(CacheKeyGenerator.menuItemsByRestaurant(1L), MenuItemResponse.class))
                .thenReturn(Optional.of(cached));
        assertSame(cached, menuService.getMenuItemsByRestaurant(1L));

        when(cacheService.getList(CacheKeyGenerator.menuItemsByRestaurant(2L), MenuItemResponse.class))
                .thenReturn(Optional.empty());
        when(menuItemRepository.findByRestaurantIdWithDetails(2L)).thenReturn(List.of(fullMenuItem(1L)));

        List<MenuItemResponse> result = menuService.getMenuItemsByRestaurant(2L);
        assertEquals(1, result.size());
        verify(cacheService).set(eq(CacheKeyGenerator.menuItemsByRestaurant(2L)), eq(result), eq(900L));
    }

    @Test
    void getMenuItemsByCategory_cacheHitAndMiss() {
        List<MenuItemResponse> cached = List.of(MenuItemResponse.builder().id(1L).build());
        when(cacheService.getList(CacheKeyGenerator.menuItemsByCategory(1L), MenuItemResponse.class))
                .thenReturn(Optional.of(cached));
        assertSame(cached, menuService.getMenuItemsByCategory(1L));

        when(cacheService.getList(CacheKeyGenerator.menuItemsByCategory(2L), MenuItemResponse.class))
                .thenReturn(Optional.empty());
        when(menuItemRepository.findByCategoryIdWithDetails(2L)).thenReturn(List.of(fullMenuItem(1L)));

        List<MenuItemResponse> result = menuService.getMenuItemsByCategory(2L);
        assertEquals(1, result.size());
        verify(cacheService).set(eq(CacheKeyGenerator.menuItemsByCategory(2L)), eq(result), eq(900L));
    }

    @Test
    void getBestsellers_cacheHitAndMiss() {
        List<MenuItemResponse> cached = List.of(MenuItemResponse.builder().id(1L).build());
        when(cacheService.getList(CacheKeyGenerator.bestsellers(1L), MenuItemResponse.class))
                .thenReturn(Optional.of(cached));
        assertSame(cached, menuService.getBestsellers(1L));

        when(cacheService.getList(CacheKeyGenerator.bestsellers(2L), MenuItemResponse.class))
                .thenReturn(Optional.empty());
        when(menuItemRepository.findBestsellersWithDetails(2L)).thenReturn(List.of(fullMenuItem(1L)));

        List<MenuItemResponse> result = menuService.getBestsellers(2L);
        assertEquals(1, result.size());
        verify(cacheService).set(eq(CacheKeyGenerator.bestsellers(2L)), eq(result), eq(900L));
    }

    @Test
    void getRecommended_filtersRecommendedItems() {
        MenuItemResponse recommended = MenuItemResponse.builder().id(1L).recommended(true).build();
        MenuItemResponse other = MenuItemResponse.builder().id(2L).recommended(false).build();
        MenuItemResponse missing = MenuItemResponse.builder().id(3L).recommended(null).build();
        when(cacheService.getList(CacheKeyGenerator.menuItemsByRestaurant(1L), MenuItemResponse.class))
                .thenReturn(Optional.of(List.of(recommended, other, missing)));

        List<MenuItemResponse> result = menuService.getRecommended(1L);

        assertEquals(List.of(recommended), result);
    }

    @Test
    void searchMenuItems_cacheHitAndMiss() {
        List<MenuItemResponse> cached = List.of(MenuItemResponse.builder().id(1L).build());
        when(cacheService.getList(CacheKeyGenerator.menuSearch("biryani"), MenuItemResponse.class))
                .thenReturn(Optional.of(cached));
        assertSame(cached, menuService.searchMenuItems("  Biryani  "));

        when(cacheService.getList(CacheKeyGenerator.menuSearch("naan"), MenuItemResponse.class))
                .thenReturn(Optional.empty());
        when(menuItemRepository.searchByNameWithDetails("Naan")).thenReturn(List.of(fullMenuItem(1L)));

        List<MenuItemResponse> result = menuService.searchMenuItems("Naan");
        assertEquals(1, result.size());
        verify(cacheService).set(eq(CacheKeyGenerator.menuSearch("naan")), eq(result), eq(300L));
    }

    @Test
    void createMenuItem_categoryNotFound_throws() {
        MenuItemRequest request = menuItemRequest();
        when(menuCategoryRepository.findByIdWithRestaurant(2L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> menuService.createMenuItem(request));
        assertEquals("Category not found", ex.getMessage());
    }

    @Test
    void createMenuItem_mapsRequestAndInvalidatesCaches() {
        MenuCategory category = category(2L, "Mains", restaurant(1L, 9L));
        when(menuCategoryRepository.findByIdWithRestaurant(2L)).thenReturn(Optional.of(category));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(menuItemRepository.save(any(MenuItem.class))).thenAnswer(inv -> {
            MenuItem saved = inv.getArgument(0);
            saved.setId(11L);
            return saved;
        });

        MenuItemResponse response = menuService.createMenuItem(menuItemRequest());

        ArgumentCaptor<MenuItem> captor = ArgumentCaptor.forClass(MenuItem.class);
        verify(menuItemRepository).save(captor.capture());
        MenuItem saved = captor.getValue();
        assertEquals("Biryani", saved.getName());
        assertEquals(250.0, saved.getPrice());
        assertEquals(300.0, saved.getOriginalPrice());
        assertEquals(16.67, saved.getDiscountPercentage());
        assertTrue(saved.getAvailable());
        assertEquals(Set.of("rice"), saved.getIngredients());
        assertEquals(Set.of("popular"), saved.getTags());
        assertEquals(Set.of("nuts"), saved.getAllergens());
        assertEquals(List.of("extra.png"), saved.getAdditionalImages());
        assertEquals(11L, response.getId());
        verify(cacheService).deletePattern("menu-item");
        verify(cacheService).deletePattern("menu-search");
        verify(cacheService).deletePattern("bestseller");
        verify(cacheService).deletePattern("recommended");
        verify(cacheService).deletePattern("menu-category");
    }

    @Test
    void createMenuItem_skipsOptionalCollectionsAndDiscountWhenNotApplicable() {
        MenuCategory category = category(2L, "Mains", restaurant(1L, 9L));
        when(menuCategoryRepository.findByIdWithRestaurant(2L)).thenReturn(Optional.of(category));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(menuItemRepository.save(any(MenuItem.class))).thenAnswer(inv -> inv.getArgument(0));

        MenuItemRequest request = new MenuItemRequest();
        request.setCategoryId(2L);
        request.setPrice(100.0);
        request.setOriginalPrice(80.0);
        menuService.createMenuItem(request);

        MenuItemRequest originalOnly = new MenuItemRequest();
        originalOnly.setCategoryId(2L);
        originalOnly.setOriginalPrice(80.0);
        menuService.createMenuItem(originalOnly);

        MenuItemRequest priceOnly = new MenuItemRequest();
        priceOnly.setCategoryId(2L);
        priceOnly.setPrice(100.0);
        menuService.createMenuItem(priceOnly);

        ArgumentCaptor<MenuItem> captor = ArgumentCaptor.forClass(MenuItem.class);
        verify(menuItemRepository, times(3)).save(captor.capture());
        assertNull(captor.getValue().getDiscountPercentage());
        assertTrue(captor.getValue().getIngredients().isEmpty());
    }

    @Test
    void updateMenuItem_notFound_throws() {
        when(menuItemRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> menuService.updateMenuItem(1L, menuItemRequest()));
    }

    @Test
    void updateMenuItem_updatesAndDeletesItemCache() {
        MenuItem item = fullMenuItem(1L);
        when(menuItemRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(item));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(menuItemRepository.save(item)).thenReturn(item);

        MenuItemResponse response = menuService.updateMenuItem(1L, menuItemRequest());

        assertEquals("Biryani", response.getName());
        verify(cacheService).delete(CacheKeyGenerator.menuItem(1L));
    }

    @Test
    void deleteMenuItem_notFound_throws() {
        when(menuItemRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> menuService.deleteMenuItem(1L));
    }

    @Test
    void deleteMenuItem_deletesAndInvalidates() {
        MenuItem item = fullMenuItem(1L);
        when(menuItemRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(item));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);

        menuService.deleteMenuItem(1L);

        verify(menuItemRepository).delete(item);
        verify(cacheService).delete(CacheKeyGenerator.menuItem(1L));
    }

    @Test
    void toggleItemAvailability_notFound_throws() {
        when(menuItemRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> menuService.toggleItemAvailability(1L, false));
    }

    @Test
    void toggleItemAvailability_updatesFlag() {
        MenuItem item = fullMenuItem(1L);
        when(menuItemRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(item));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);

        menuService.toggleItemAvailability(1L, false);

        assertEquals(false, item.getAvailable());
        verify(menuItemRepository).save(item);
        verify(cacheService).delete(CacheKeyGenerator.menuItem(1L));
    }

    @Test
    void verifyOwnership_rethrowsUnauthorizedFromSecurityUtils() {
        Restaurant restaurant = restaurant(1L, 9L);
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenThrow(new UnauthorizedException("token expired"));

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> menuService.createCategory(1L, categoryRequest("X", "Y", 1, true)));
        assertEquals("token expired", ex.getMessage());
    }

    private MenuCategoryRequest categoryRequest(String name, String description, Integer order, Boolean active) {
        MenuCategoryRequest request = new MenuCategoryRequest();
        request.setName(name);
        request.setDescription(description);
        request.setDisplayOrder(order);
        request.setActive(active);
        return request;
    }

    private MenuItemRequest menuItemRequest() {
        MenuItemRequest request = new MenuItemRequest();
        request.setName("Biryani");
        request.setDescription("Hyderabadi");
        request.setCategoryId(2L);
        request.setPrice(250.0);
        request.setOriginalPrice(300.0);
        request.setFoodType(MenuItem.FoodType.NON_VEG);
        request.setIsVeg(false);
        request.setIsSpicy(true);
        request.setSpiceLevel(MenuItem.SpiceLevel.HOT);
        request.setImageUrl("img.png");
        request.setPreparationTime(20);
        request.setCalories(600);
        request.setServingSize("1 plate");
        request.setIngredients(Set.of("rice"));
        request.setTags(Set.of("popular"));
        request.setAllergens(Set.of("nuts"));
        request.setAdditionalImages(List.of("extra.png"));
        return request;
    }

    private Restaurant restaurant(Long id, Long ownerId) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(ownerId);
        restaurant.setOwner(owner);
        return restaurant;
    }

    private MenuCategory category(Long id, String name, Restaurant restaurant) {
        MenuCategory category = new MenuCategory();
        category.setId(id);
        category.setName(name);
        category.setDescription("desc");
        category.setRestaurant(restaurant);
        category.setDisplayOrder(1);
        category.setActive(true);
        return category;
    }

    private MenuItem fullMenuItem(Long id) {
        MenuItem item = new MenuItem();
        item.setId(id);
        item.setName("Biryani");
        item.setDescription("Hyderabadi");
        item.setCategory(category(2L, "Mains", restaurant(1L, 9L)));
        item.setPrice(250.0);
        item.setOriginalPrice(300.0);
        item.setDiscountPercentage(16.67);
        item.setAvailable(true);
        item.setFoodType(MenuItem.FoodType.NON_VEG);
        item.setIsVeg(false);
        item.setIsSpicy(true);
        item.setSpiceLevel(MenuItem.SpiceLevel.HOT);
        item.setImageUrl("img.png");
        item.setPreparationTime(20);
        item.setBestseller(true);
        item.setRecommended(true);
        item.setCalories(600);
        item.setServingSize("1 plate");
        item.setAverageRating(4.5);
        item.setTotalRatings(10);
        item.setTags(new HashSet<>(Set.of("popular")));
        item.setAllergens(new HashSet<>(Set.of("nuts")));
        item.setIngredients(new HashSet<>(Set.of("rice")));
        return item;
    }
}
