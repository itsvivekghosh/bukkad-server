package com.bhukkad.catalog.personalization.domain.service.impl;

import com.bhukkad.catalog.personalization.domain.service.RecommendationQueryService;
import com.bhukkad.catalog.personalization.infrastructure.client.OrderHistoryClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationQueryServiceImpl implements RecommendationQueryService {

    private final OrderHistoryClient orderHistoryClient;

    /**
     * Order history is fetched from commerce over the mesh.
     *
     * <p>This class previously ran the aggregate SQL directly through catalog's
     * own {@code JdbcTemplate}. {@code orders}/{@code order_items} live in the
     * commerce schema, so every call raised {@code BadSqlGrammarException}
     * ("relation does not exist") and the recommendation endpoints returned
     * 500. Reading across the service boundary keeps order data owned by
     * commerce and lets the client degrade to an empty signal instead of
     * failing the request.</p>
     */
    @Override
    public List<Object[]> findCustomerItemFrequencies(Long customerId, int limit) {
        List<OrderHistoryClient.ItemFrequency> rows = orderHistoryClient.itemFrequencies(customerId, limit);
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(r -> new Object[]{r.menuItemId(), r.frequency()})
                .toList();
    }

    @Override
    public List<Object[]> findCoOrderedItems(List<Long> itemIds, Long customerId, int limit) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        List<OrderHistoryClient.ItemFrequency> rows = orderHistoryClient.coOrdered(itemIds, customerId, limit);
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(r -> new Object[]{r.menuItemId(), r.frequency()})
                .toList();
    }

    @Override
    public List<Object[]> findCustomerRestaurantAffinities(Long customerId, int limit) {
        List<OrderHistoryClient.RestaurantAffinity> rows = orderHistoryClient.restaurantAffinities(customerId, limit);
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(r -> new Object[]{r.restaurantId(), r.visits()})
                .toList();
    }
}
