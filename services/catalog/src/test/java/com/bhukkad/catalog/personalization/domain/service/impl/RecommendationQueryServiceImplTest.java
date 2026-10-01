package com.bhukkad.catalog.personalization.domain.service.impl;

import com.bhukkad.catalog.personalization.infrastructure.client.OrderHistoryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The implementation reads order history from commerce over the mesh instead of
 * running the aggregate SQL locally (those tables live in the commerce schema).
 * These tests cover the delegation, the {@code Object[]} shape the
 * personalization service consumes, and degradation when commerce is down.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationQueryServiceImplTest {

    @Mock private OrderHistoryClient orderHistoryClient;

    private RecommendationQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RecommendationQueryServiceImpl(orderHistoryClient);
    }

    @Test
    void findCustomerItemFrequencies_mapsIdsAndFrequencies() {
        when(orderHistoryClient.itemFrequencies(7L, 5))
                .thenReturn(List.of(new OrderHistoryClient.ItemFrequency(41L, 3L)));

        assertThat(service.findCustomerItemFrequencies(7L, 5))
                .containsExactly(new Object[]{41L, 3L});

        verify(orderHistoryClient).itemFrequencies(7L, 5);
    }

    @Test
    void findCustomerItemFrequencies_nullResponse_degradesToEmpty() {
        when(orderHistoryClient.itemFrequencies(7L, 5)).thenReturn(null);

        assertThat(service.findCustomerItemFrequencies(7L, 5)).isEmpty();
    }

    @Test
    void findCoOrderedItems_nullOrEmptyItemIds_skipsCall() {
        assertThat(service.findCoOrderedItems(List.of(), 1L, 10)).isEmpty();
        assertThat(service.findCoOrderedItems(null, 1L, 10)).isEmpty();

        verifyNoInteractions(orderHistoryClient);
    }

    @Test
    void findCoOrderedItems_mapsMenuItemAndOccurrence() {
        when(orderHistoryClient.coOrdered(List.of(11L, 12L), 7L, 30))
                .thenReturn(List.of(new OrderHistoryClient.ItemFrequency(12L, 4L)));

        assertThat(service.findCoOrderedItems(List.of(11L, 12L), 7L, 30))
                .containsExactly(new Object[]{12L, 4L});

        verify(orderHistoryClient).coOrdered(List.of(11L, 12L), 7L, 30);
    }

    @Test
    void findCustomerRestaurantAffinities_mapsRestaurantAndVisits() {
        when(orderHistoryClient.restaurantAffinities(7L, 9))
                .thenReturn(List.of(new OrderHistoryClient.RestaurantAffinity(3L, 8L)));

        assertThat(service.findCustomerRestaurantAffinities(7L, 9))
                .containsExactly(new Object[]{3L, 8L});

        verify(orderHistoryClient).restaurantAffinities(7L, 9);
    }
}
