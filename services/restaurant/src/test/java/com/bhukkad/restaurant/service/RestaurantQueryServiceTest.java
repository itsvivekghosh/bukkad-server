package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.restaurant.api.MenuSnapshot;
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

    @InjectMocks
    private RestaurantQueryService service;

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
        when(restaurantRepository.findByCuisineIdAndIsActiveTrue(1L))
                .thenReturn(List.of(restaurant(1L, "Spice Garden")));

        List<RestaurantSummary> result = service.browseByCuisine(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Spice Garden");
        assertThat(result.get(0).avgRating()).isEqualTo(4.5);
    }

    @Test
    void search_mapsResults() {
        when(restaurantRepository.findByNameContainingIgnoreCaseAndIsActiveTrue("pizza"))
                .thenReturn(List.of(restaurant(2L, "Pizza Palace")));

        List<RestaurantSummary> result = service.search("pizza");

        assertThat(result).extracting(RestaurantSummary::name).containsExactly("Pizza Palace");
    }

    @Test
    void menuSnapshot_returnsAvailableItems() {
        Restaurant r = restaurant(1L, "Spice Garden");
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(r));
        MenuItem m = new MenuItem();
        m.setId(10L);
        m.setName("Paneer");
        m.setPrice(new BigDecimal("240.00"));
        m.setIsAvailable(true);
        when(menuItemRepository.findByRestaurantIdAndIsAvailableTrue(1L)).thenReturn(List.of(m));

        MenuSnapshot snapshot = service.menuSnapshot(1L);

        assertThat(snapshot.restaurantName()).isEqualTo("Spice Garden");
        assertThat(snapshot.items()).hasSize(1);
        assertThat(snapshot.items().get(0).price()).isEqualByComparingTo("240.00");
    }

    @Test
    void menuSnapshot_unknownRestaurant_throws() {
        when(restaurantRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.menuSnapshot(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void menuSnapshot_skipsUnavailableItems() {
        Restaurant r = restaurant(1L, "Spice Garden");
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(r));
        MenuItem available = new MenuItem();
        available.setId(1L);
        available.setPrice(new BigDecimal("10.00"));
        available.setIsAvailable(true);
        MenuItem hidden = new MenuItem();
        hidden.setId(2L);
        hidden.setPrice(new BigDecimal("20.00"));
        hidden.setIsAvailable(false);
        when(menuItemRepository.findByRestaurantIdAndIsAvailableTrue(1L)).thenReturn(List.of(available));

        MenuSnapshot snapshot = service.menuSnapshot(1L);

        assertThat(snapshot.items()).extracting(m -> m.id()).containsExactly(1L);
    }
}
