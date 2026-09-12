package com.bhukkad.search.domain.service.impl;

import com.bhukkad.search.domain.repository.MenuItemSearchRepository;
import com.bhukkad.search.domain.repository.RestaurantSearchRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Projection payload mapping for the event consumer and the reconciliation
 * sweep: full-column upserts, null/missing tolerance and repair routing.
 */
@ExtendWith(MockitoExtension.class)
class SearchSyncProjectionServiceTest {

    @Mock private RestaurantSearchRepository restaurantSearchRepository;
    @Mock private MenuItemSearchRepository menuItemSearchRepository;

    private SearchSyncProjectionService service() {
        return new SearchSyncProjectionService(restaurantSearchRepository, menuItemSearchRepository);
    }

    @Test
    void upsertRestaurant_mapsMissingAndNullFieldsToNulls() throws Exception {
        JsonNode data = new ObjectMapper().readTree(
                "{\"name\":\"Spice Route\",\"description\":null,\"averageRating\":4.5,\"isOpen\":true}");

        service().upsertRestaurant(7L, data);

        verify(restaurantSearchRepository).upsertFromEvent(
                eq(7L), eq("Spice Route"), isNull(), isNull(),
                eq(Boolean.TRUE), isNull(), eq(4.5), isNull(), isNull());
    }

    @Test
    void upsertMenuItem_mapsEveryColumn() throws Exception {
        JsonNode data = new ObjectMapper().readTree("""
                {"name":"Dal","description":"y","restaurantId":5,"price":180.5,
                 "originalPrice":220.0,"discountPercentage":17.9,"available":false,
                 "foodType":"VEG","isVeg":true,"imageUrl":"i.png","preparationTime":30,
                 "bestseller":false,"restaurantName":"Spice"}""");

        service().upsertMenuItem(50L, data);

        verify(menuItemSearchRepository).upsertFromEvent(
                eq(50L), eq(5L), eq("Dal"), eq("y"), eq(180.5), eq(220.0), eq(17.9),
                eq(Boolean.FALSE), eq("VEG"), eq(Boolean.TRUE), eq("i.png"), eq(30),
                eq(Boolean.FALSE), eq("Spice"));
    }

    @Test
    void deleteMenuItem_propagatesById() {
        service().deleteMenuItem(50L, null);

        verify(menuItemSearchRepository).deleteById(50L);
    }

    @Test
    void upsertMenuItemFromSource_routesToRepairStatement() {
        service().upsertMenuItemFromSource(50L, 5L, "Spice", "Dal", null, 180.0, true);

        verify(menuItemSearchRepository).repairFromSource(
                50L, 5L, "Dal", null, 180.0, true, "Spice");
    }

    @Test
    void finders_returnNull_whenAbsent() {
        when(restaurantSearchRepository.findById(1L)).thenReturn(Optional.empty());
        when(menuItemSearchRepository.findById(2L)).thenReturn(Optional.empty());

        var service = service();
        assertThat(service.findRestaurant(1L)).isNull();
        assertThat(service.findMenuItem(2L)).isNull();
    }

    @Test
    void finder_returnsEntity_whenPresent() {
        var entity = new com.bhukkad.search.domain.entity.MenuItemSearchEntity();
        entity.setId(3L);
        when(menuItemSearchRepository.findById(3L)).thenReturn(Optional.of(entity));

        assertThat(service().findMenuItem(3L).getId()).isEqualTo(3L);
    }
}
