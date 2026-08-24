package com.bhukkad.recommendation;

import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.mapper.MenuItemMapper;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.OrderItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SurpriseMeServiceTest {

    @Mock
    private MenuItemRepository menuItemRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private MenuItemMapper menuItemMapper;

    private SurpriseMeService service() {
        return new SurpriseMeService(menuItemRepository, orderItemRepository, menuItemMapper);
    }

    private MenuItem item(Long id, double rating, int ratings) {
        MenuItem item = new MenuItem();
        item.setId(id);
        item.setAverageRating(rating);
        item.setTotalRatings(ratings);
        return item;
    }

    private MenuItemResponse response(Long id) {
        return MenuItemResponse.builder().id(id).name("item-" + id).build();
    }

    @Test
    void surprisePick_picksHighestRatedItem() {
        MenuItem best = item(1L, 4.5, 120);
        MenuItem second = item(2L, 4.2, 500);
        when(menuItemRepository.findByRestaurantIdWithDetails(10L)).thenReturn(List.of(second, best));
        when(menuItemMapper.toResponse(best)).thenReturn(response(1L));

        Optional<MenuItemResponse> pick = service().surprisePick(7L, 10L);

        assertTrue(pick.isPresent());
        assertEquals(1L, pick.get().getId());
        verify(orderItemRepository, never()).countByMenuItemId(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void surprisePick_unratedItems_fallsBackToMostOrdered() {
        MenuItem a = item(1L, 0.0, 0);
        MenuItem b = item(2L, 0.0, 0);
        when(menuItemRepository.findByRestaurantIdWithDetails(10L)).thenReturn(List.of(a, b));
        when(orderItemRepository.countByMenuItemId(1L)).thenReturn(5L);
        when(orderItemRepository.countByMenuItemId(2L)).thenReturn(9L);
        when(menuItemMapper.toResponse(b)).thenReturn(response(2L));

        Optional<MenuItemResponse> pick = service().surprisePick(7L, 10L);

        assertTrue(pick.isPresent());
        assertEquals(2L, pick.get().getId());
    }

    @Test
    void surprisePick_nullRatingTreatedAsUnrated() {
        MenuItem a = item(1L, 4.0, 10);
        MenuItem b = new MenuItem();
        b.setId(2L);
        when(menuItemRepository.findByRestaurantIdWithDetails(10L)).thenReturn(List.of(a, b));
        when(menuItemMapper.toResponse(a)).thenReturn(response(1L));

        Optional<MenuItemResponse> pick = service().surprisePick(7L, 10L);

        assertTrue(pick.isPresent());
        assertEquals(1L, pick.get().getId());
    }

    @Test
    void surprisePick_noAvailableItems_returnsEmpty() {
        when(menuItemRepository.findByRestaurantIdWithDetails(10L)).thenReturn(List.of());

        Optional<MenuItemResponse> pick = service().surprisePick(7L, 10L);

        assertFalse(pick.isPresent());
    }

    @Test
    void surprisePick_mappingFailure_returnsEmpty() {
        MenuItem best = item(1L, 4.5, 120);
        when(menuItemRepository.findByRestaurantIdWithDetails(10L)).thenReturn(List.of(best));
        when(menuItemMapper.toResponse(best)).thenThrow(new RuntimeException("mapper boom"));

        Optional<MenuItemResponse> pick = service().surprisePick(7L, 10L);

        assertFalse(pick.isPresent());
    }

    @Test
    void surprisePick_repositoryFailure_returnsEmpty() {
        when(menuItemRepository.findByRestaurantIdWithDetails(10L))
                .thenThrow(new RuntimeException("db down"));

        Optional<MenuItemResponse> pick = service().surprisePick(7L, 10L);

        assertFalse(pick.isPresent());
    }
}
