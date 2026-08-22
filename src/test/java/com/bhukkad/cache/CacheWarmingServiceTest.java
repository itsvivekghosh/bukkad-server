package com.bhukkad.cache;

import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.service.MenuService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheWarmingServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private MenuService menuService;

    @Mock
    private RedisCacheService cacheService;

    @InjectMocks
    private CacheWarmingService warmingService;

    @Test
    void warmRestaurantCaches_preloadsActiveAndTopRated() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setName("Test Restaurant");

        when(restaurantRepository.findAllActiveWithDetails()).thenReturn(List.of(restaurant));
        when(restaurantRepository.findTop10ByIsActiveTrueOrderByAverageRatingDesc()).thenReturn(List.of(restaurant));

        warmingService.warmRestaurantCaches();

        verify(cacheService).set(eq(CacheKeyGenerator.restaurantList()), anyList(), eq(600L));
        verify(cacheService).set(eq(CacheKeyGenerator.restaurant(1L)), any(RestaurantResponse.class), eq(1800L));
    }

    @Test
    void warmMenuCaches_preloadsCategoriesAndItemsForTopRestaurants() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);

        when(restaurantRepository.findTop10ByIsActiveTrueOrderByAverageRatingDesc()).thenReturn(List.of(restaurant));

        warmingService.warmMenuCaches();

        verify(menuService).getCategoriesByRestaurant(1L);
        verify(menuService).getMenuItemsByRestaurant(1L);
        verify(menuService).getBestsellers(1L);
        verify(menuService).getRecommended(1L);
    }

    @Test
    void warmMenuCaches_handlesFailureGracefully() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);

        when(restaurantRepository.findTop10ByIsActiveTrueOrderByAverageRatingDesc()).thenReturn(List.of(restaurant));
        doThrow(new RuntimeException("db down")).when(menuService).getCategoriesByRestaurant(anyLong());

        // Should not throw - failures are caught and logged
        warmingService.warmMenuCaches();

        verify(menuService).getCategoriesByRestaurant(1L);
    }
}
