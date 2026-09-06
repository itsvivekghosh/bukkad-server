package com.bhukkad.personalization.serviceImpl;

import com.bhukkad.personalization.service.RecommendationQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationQueryServiceImpl implements RecommendationQueryService {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<Object[]> findCustomerItemFrequencies(Long customerId, int limit) {
        String sql = """
            SELECT oi.menu_item_id, COUNT(*) as frequency
            FROM orders o
            JOIN order_items oi ON o.id = oi.order_id
            WHERE o.customer_id = ? AND o.status = 'DELIVERED'
            GROUP BY oi.menu_item_id
            ORDER BY frequency DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new Object[]{rs.getLong("menu_item_id"), rs.getLong("frequency")}, customerId, limit);
    }

    @Override
    public List<Object[]> findCoOrderedItems(List<Long> itemIds, Long customerId, int limit) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", itemIds.stream().map(id -> "?").toList());
        String sql = String.format("""
            SELECT co.menu_item_id, COUNT(*) as co_occurrence
            FROM order_items oi
            JOIN orders o ON oi.order_id = o.id
            JOIN order_items co ON o.id = co.order_id AND co.menu_item_id != oi.menu_item_id
            WHERE oi.menu_item_id IN (%s) AND o.status = 'DELIVERED'
              AND co.menu_item_id NOT IN (SELECT menu_item_id FROM order_items oi2 JOIN orders o2 ON oi2.order_id = o2.id WHERE o2.customer_id = ? AND o2.status = 'DELIVERED')
            GROUP BY co.menu_item_id
            ORDER BY co_occurrence DESC
            LIMIT ?
            """, placeholders);
        Object[] params = new Object[itemIds.size() + 2];
        int idx = 0;
        for (Long id : itemIds) {
            params[idx++] = id;
        }
        params[idx++] = customerId;
        params[idx] = limit;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new Object[]{rs.getLong("menu_item_id"), rs.getLong("co_occurrence")}, params);
    }

    @Override
    public List<Object[]> findCustomerRestaurantAffinities(Long customerId, int limit) {
        String sql = """
            SELECT o.restaurant_id, COUNT(*) as visits
            FROM orders o
            WHERE o.customer_id = ? AND o.status = 'DELIVERED'
            GROUP BY o.restaurant_id
            ORDER BY visits DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new Object[]{rs.getLong("restaurant_id"), rs.getLong("visits")}, customerId, limit);
    }
}
