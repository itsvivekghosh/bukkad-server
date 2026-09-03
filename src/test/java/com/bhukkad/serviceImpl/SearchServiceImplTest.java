package com.bhukkad.serviceImpl;

import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.dto.response.UnifiedSearchResponse;
import com.bhukkad.common.metrics.BusinessMetrics;
import com.bhukkad.service.MenuService;
import com.bhukkad.service.RestaurantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private RestaurantService restaurantService;
    @Mock
    private MenuService menuService;
    @Mock
    private BusinessMetrics businessMetrics;

    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SearchServiceImpl(restaurantService, menuService, businessMetrics);
    }

    @Test void unifiedSearch_combinesRestaurantsAndMenuItems() {
        when(restaurantService.searchRestaurants("pizza")).thenReturn(List.of(new RestaurantResponse()));
        when(menuService.searchMenuItems("pizza")).thenReturn(List.of(new MenuItemResponse(), new MenuItemResponse()));

        UnifiedSearchResponse response = service.unifiedSearch("pizza");

        assertEquals(1, response.getRestaurantCount());
        assertEquals(2, response.getMenuItemCount());
        assertEquals(1, response.getRestaurants().size());
        assertEquals(2, response.getMenuItems().size());
        verify(businessMetrics).search();
    }

    @Test void unifiedSearch_emptyResults_returnsZeroCounts() {
        when(restaurantService.searchRestaurants("zzz")).thenReturn(List.of());
        when(menuService.searchMenuItems("zzz")).thenReturn(List.of());

        UnifiedSearchResponse response = service.unifiedSearch("zzz");

        assertEquals(0, response.getRestaurantCount());
        assertEquals(0, response.getMenuItemCount());
        assertTrue(response.getRestaurants().isEmpty());
        assertTrue(response.getMenuItems().isEmpty());
        Mockito.verify(businessMetrics).search();
    }

    @Test void unifiedSearch_restaurantsOnly_keepsMenuEmpty() {
        when(restaurantService.searchRestaurants("paneer")).thenReturn(List.of(new RestaurantResponse()));
        when(menuService.searchMenuItems("paneer")).thenReturn(List.of());

        UnifiedSearchResponse response = service.unifiedSearch("paneer");

        assertEquals(1, response.getRestaurantCount());
        assertEquals(0, response.getMenuItemCount());
    }

    @Test void unifiedSearch_nullKeyword_returnsEmptyWithoutDelegating() {
        // Batch B guard: degenerate keywords never reach the LIKE/FULLTEXT queries.
        UnifiedSearchResponse response = service.unifiedSearch(null);

        assertEquals(0, response.getRestaurantCount());
        assertEquals(0, response.getMenuItemCount());
        assertTrue(response.getRestaurants().isEmpty());
        assertTrue(response.getMenuItems().isEmpty());
        verify(restaurantService, Mockito.never()).searchRestaurants(Mockito.anyString());
        verify(menuService, Mockito.never()).searchMenuItems(Mockito.anyString());
    }

    @Test void unifiedSearch_shortKeyword_returnsEmptyWithoutDelegating() {
        UnifiedSearchResponse response = service.unifiedSearch("  p  ");

        assertEquals(0, response.getRestaurantCount());
        assertEquals(0, response.getMenuItemCount());
        verify(restaurantService, Mockito.never()).searchRestaurants(Mockito.anyString());
        verify(menuService, Mockito.never()).searchMenuItems(Mockito.anyString());
    }

    @Test void unifiedSearch_singleCharKeyword_returnsEmptyWithoutDelegating() {
        UnifiedSearchResponse response = service.unifiedSearch("a");

        assertEquals(0, response.getRestaurantCount());
        assertEquals(0, response.getMenuItemCount());
        verify(restaurantService, Mockito.never()).searchRestaurants(Mockito.anyString());
        verify(menuService, Mockito.never()).searchMenuItems(Mockito.anyString());
    }
}
