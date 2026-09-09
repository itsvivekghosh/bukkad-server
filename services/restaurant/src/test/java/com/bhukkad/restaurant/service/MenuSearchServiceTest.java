package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.api.MenuItemDto;
import com.bhukkad.restaurant.api.RestaurantSummary;
import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(restaurantRepository.fullTextSearchByName("biryani", 20))
                .thenReturn(List.of(r));

        List<RestaurantSummary> results = service.searchRestaurants("  biryani  ");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Biryani House");
        assertThat(results.get(0).avgRating()).isEqualTo(4.5);
        verify(restaurantRepository).fullTextSearchByName("biryani", 20);
    }

    @Test
    void searchMenuItems_mapsToDto() {
        MenuItem m = new MenuItem();
        m.setId(7L);
        m.setName("Butter Chicken");
        m.setDescription("creamy");
        m.setPrice(new BigDecimal("320.00"));
        m.setIsAvailable(true);
        when(menuItemRepository.fullTextSearch("butter", 20)).thenReturn(List.of(m));

        List<MenuItemDto> results = service.searchMenuItems("butter");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Butter Chicken");
        assertThat(results.get(0).available()).isTrue();
        verify(menuItemRepository).fullTextSearch("butter", 20);
    }

    @Test
    void searchMenuItems_unavailable_mapsToFalse() {
        MenuItem m = new MenuItem();
        m.setId(7L);
        m.setName("Soup");
        m.setDescription("hot");
        m.setPrice(new BigDecimal("120.00"));
        m.setIsAvailable(false);
        when(menuItemRepository.fullTextSearch("soup", 20)).thenReturn(List.of(m));

        assertThat(service.searchMenuItems("soup").get(0).available()).isFalse();
    }
}