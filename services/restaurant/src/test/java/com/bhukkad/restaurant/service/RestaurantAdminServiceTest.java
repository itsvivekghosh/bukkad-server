package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Restaurant write operations: create, duplicate guard, availability toggle.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantAdminServiceTest {

    @Mock private RestaurantRepository restaurantRepository;
    @Mock private RestaurantEventPublisher eventPublisher;
    @Mock private com.bhukkad.restaurant.service.cache.MenuCacheInvalidator cacheInvalidator;
    @InjectMocks private RestaurantAdminService service;

    @Test
    void create_savesAndPublishes() {
        when(restaurantRepository.findByNameContainingIgnoreCaseAndIsActiveTrue("Test"))
                .thenReturn(List.of());
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> {
            Restaurant r = inv.getArgument(0);
            r.setId(5L);
            return r;
        });

        Restaurant saved = service.create("Test", "desc", 1L, "addr", "9999999999");

        assertThat(saved.getName()).isEqualTo("Test");
        assertThat(saved.getDescription()).isEqualTo("desc");
        assertThat(saved.getCuisineId()).isEqualTo(1L);
        assertThat(saved.getAddress()).isEqualTo("addr");
        assertThat(saved.getPhone()).isEqualTo("9999999999");
        assertThat(saved.getIsActive()).isTrue();
        verify(eventPublisher).restaurantCreated(5L, "Test");
        verify(cacheInvalidator).invalidateFeed(); // PERF-3: new restaurant changes the feed
    }

    @Test
    void create_duplicate_throws() {
        when(restaurantRepository.findByNameContainingIgnoreCaseAndIsActiveTrue("Test"))
                .thenReturn(List.of(new Restaurant()));

        assertThatThrownBy(() -> service.create("Test", "desc", 1L, "addr", "9999999999"))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void updateAvailability_togglesAndPublishes() {
        Restaurant r = new Restaurant();
        r.setId(3L);
        r.setIsActive(true);
        when(restaurantRepository.findById(3L)).thenReturn(Optional.of(r));
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> inv.getArgument(0));

        Restaurant updated = service.updateAvailability(3L, false);

        assertThat(updated.getIsActive()).isFalse();
        verify(eventPublisher).availabilityChanged(3L, false);
        verify(cacheInvalidator).invalidateFeed(); // PERF-3: activation change invalidates feed
    }

    @Test
    void updateAvailability_unknown_throws() {
        when(restaurantRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAvailability(99L, true))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }
}