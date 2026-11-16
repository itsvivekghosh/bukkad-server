package com.bhukkad.catalog.restaurant.domain.service.impl;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.catalog.restaurant.api.dto.response.MenuSnapshot;
import com.bhukkad.catalog.restaurant.api.dto.response.RestaurantSummary;
import com.bhukkad.catalog.restaurant.domain.entity.MenuItem;
import com.bhukkad.catalog.restaurant.domain.repository.MenuItemRepository;
import com.bhukkad.catalog.restaurant.domain.entity.Restaurant;
import com.bhukkad.catalog.restaurant.domain.repository.RestaurantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantQueryServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private MenuItemRepository menuItemRepository;

    private RestaurantQueryService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = newService(false);
    }

    /** No-Redis variant = pre-PERF-3 direct-DB behaviour (what this suite asserts). */
    private RestaurantQueryService newService(boolean withCache) {
        return new RestaurantQueryService(restaurantRepository, menuItemRepository,
                new com.bhukkad.catalog.restaurant.testsupport.FixedObjectProvider<>(
                        withCache ? org.mockito.Mockito.mock(com.bhukkad.common.cache.RedisCacheService.class) : null),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private Restaurant restaurant(Long id, String name) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setName(name);
        r.setCuisineId(1L);
        r.setIsActive(true);
        r.setAvgRating(4.5);
        return r;
    }

    @Test
    void browseByCuisine_returnsActiveOnly() {
        Page<Restaurant> page = new org.springframework.data.domain.PageImpl<>(
                List.of(restaurant(1L, "Spice Garden")), PageRequest.of(0, 10), 1);
        when(restaurantRepository.findByCuisineIdAndIsActiveTrue(1L, PageRequest.of(0, 10)))
                .thenReturn(page);

        Page<RestaurantSummary> result = service.browseByCuisine(1L, PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Spice Garden");
        assertThat(result.getContent().get(0).avgRating()).isEqualTo(4.5);
    }

    @Test
    void search_mapsResults() {
        Page<Restaurant> page = new org.springframework.data.domain.PageImpl<>(
                List.of(restaurant(2L, "Pizza Palace")), PageRequest.of(0, 10), 1);
        when(restaurantRepository.findByNameContainingIgnoreCaseAndIsActiveTrue("pizza", PageRequest.of(0, 10)))
                .thenReturn(page);

        Page<RestaurantSummary> result = service.search("pizza", PageRequest.of(0, 10));

        assertThat(result).extracting(RestaurantSummary::name).containsExactly("Pizza Palace");
    }

    @Test
    void menuSnapshot_returnsAvailableItems() {
        MenuItem mi = new MenuItem();
        mi.setId(1L);
        mi.setRestaurantId(1L);
        mi.setName("Paneer Butter Masala");
        mi.setDescription("Rich creamy curry");
        mi.setPrice(new BigDecimal("320.00"));
        mi.setIsAvailable(true);
        when(menuItemRepository.findByRestaurantIdAndIsAvailableTrue(1L))
                .thenReturn(List.of(mi));
        Restaurant r = restaurant(1L, "Paneer Butter Masala");
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(r));

        MenuSnapshot result = service.menuSnapshot(1L);

        assertThat(result.restaurantId()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).name()).isEqualTo("Paneer Butter Masala");
        assertThat(result.items().get(0).price()).isEqualByComparingTo("320.00");
    }

    @Test
    void search_returnsEmptyWhenNoResults() {
        Page<Restaurant> page = new org.springframework.data.domain.PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(restaurantRepository.findByNameContainingIgnoreCaseAndIsActiveTrue("nonexistent", PageRequest.of(0, 10)))
                .thenReturn(page);

        Page<RestaurantSummary> result = service.search("nonexistent", PageRequest.of(0, 10));

        assertThat(result).isEmpty();
    }
}
