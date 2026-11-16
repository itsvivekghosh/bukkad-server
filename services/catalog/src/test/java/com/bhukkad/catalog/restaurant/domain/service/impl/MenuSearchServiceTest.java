package com.bhukkad.catalog.restaurant.domain.service.impl;

import com.bhukkad.catalog.restaurant.api.dto.response.MenuItemDto;
import com.bhukkad.catalog.restaurant.api.dto.response.RestaurantSummary;
import com.bhukkad.catalog.restaurant.domain.entity.MenuItem;
import com.bhukkad.catalog.restaurant.domain.repository.MenuItemRepository;
import com.bhukkad.catalog.restaurant.domain.entity.Restaurant;
import com.bhukkad.catalog.restaurant.domain.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full-text search over restaurants and menu items (tsvector port).
 */
@ExtendWith(MockitoExtension.class)
class MenuSearchServiceTest {

    @Mock private RestaurantRepository restaurantRepository;
    @Mock private MenuItemRepository menuItemRepository;
    @InjectMocks private MenuSearchService service;

    @Test
    void searchRestaurants_mapsToSummary() {
        Restaurant r = new Restaurant();
        r.setId(1L);
        r.setName("Biryani House");
        r.setDescription("Hyderabadi");
        r.setCuisineId(2L);
        r.setAddress("MG Road");
        r.setPhone("9999999999");
        r.setIsActive(true);
        r.setAvgRating(4.5);
        Page<Restaurant> page = new org.springframework.data.domain.PageImpl<>(List.of(r), PageRequest.of(0, 20), 1);
        when(restaurantRepository.fullTextSearchByName("biryani", PageRequest.of(0, 20)))
                .thenReturn(page);

        Page<RestaurantSummary> results = service.searchRestaurants("  biryani  ", PageRequest.of(0, 20));

        assertThat(results).hasSize(1);
        assertThat(results.getContent().get(0).name()).isEqualTo("Biryani House");
        assertThat(results.getContent().get(0).avgRating()).isEqualTo(4.5);
        verify(restaurantRepository).fullTextSearchByName("biryani", PageRequest.of(0, 20));
    }

    @Test
    void searchMenuItems_mapsToDto() {
        MenuItem m = new MenuItem();
        m.setId(7L);
        m.setName("Butter Chicken");
        m.setDescription("creamy");
        m.setPrice(new BigDecimal("320.00"));
        m.setIsAvailable(true);
        Page<MenuItem> page = new org.springframework.data.domain.PageImpl<>(List.of(m), PageRequest.of(0, 20), 1);
        when(menuItemRepository.fullTextSearch(anyString(), any(Pageable.class))).thenReturn(page);

        Page<MenuItemDto> results = service.searchMenuItems("butter", PageRequest.of(0, 10));

        assertThat(results).hasSize(1);
        assertThat(results.getContent().get(0).name()).isEqualTo("Butter Chicken");
        assertThat(results.getContent().get(0).available()).isTrue();
        verify(menuItemRepository).fullTextSearch(anyString(), any(Pageable.class));
    }

    @Test
    void searchMenuItems_unavailable_mapsToFalse() {
        MenuItem m = new MenuItem();
        m.setId(7L);
        m.setName("Soup");
        m.setDescription("hot");
        m.setPrice(new BigDecimal("120.00"));
        m.setIsAvailable(false);
        Page<MenuItem> page = new org.springframework.data.domain.PageImpl<>(List.of(m), PageRequest.of(0, 20), 1);
        when(menuItemRepository.fullTextSearch(anyString(), any(Pageable.class))).thenReturn(page);

        assertThat(service.searchMenuItems("soup", PageRequest.of(0, 10)).getContent().get(0).available()).isFalse();
    }
}
