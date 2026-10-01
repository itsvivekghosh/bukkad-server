package com.bhukkad.commerce.order.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Service-to-service recommendation aggregates ({@code /api/v1/internal/recommendations/**}).
 *
 * <p>catalog's personalization engine needs order history to build "for you",
 * reorder and time-aware suggestions, but {@code orders}/{@code order_items} are
 * owned by commerce. Catalog previously issued this SQL against its own
 * datasource, where those tables do not exist, so every recommendation request
 * died with {@code BadSqlGrammarException} and surfaced as a 500. Order data
 * now stays behind this boundary and is read over the mesh instead.</p>
 *
 * <p>Access mirrors {@link InternalOrderController}: {@code ServiceJwtAuthFilter}
 * guards the path and method security restricts to ROLE_SERVICE / ROLE_ADMIN.</p>
 */
@RestController
@RequestMapping("/api/v1/internal/recommendations")
@RequiredArgsConstructor
public class InternalRecommendationController {

    private final JdbcTemplate jdbcTemplate;

    /** {@code [menuItemId, frequency]} for the customer's delivered orders. */
    public record ItemFrequency(Long menuItemId, long frequency) {
    }

    /** {@code [restaurantId, visits]} for the customer's delivered orders. */
    public record RestaurantAffinity(Long restaurantId, long visits) {
    }

    public record CoOrderedResponse(List<ItemFrequency> items) {
    }

    @GetMapping("/customers/{customerId}/item-frequencies")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public List<ItemFrequency> itemFrequencies(
            @org.springframework.web.bind.annotation.PathVariable Long customerId,
            @RequestParam(defaultValue = "10") int limit) {
        String sql = """
            SELECT oi.menu_item_id, COUNT(*) AS frequency
            FROM orders o
            JOIN order_items oi ON o.id = oi.order_id
            WHERE o.customer_id = ? AND o.status = 'DELIVERED'
            GROUP BY oi.menu_item_id
            ORDER BY frequency DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new ItemFrequency(rs.getLong("menu_item_id"), rs.getLong("frequency")),
                customerId, clampLimit(limit));
    }

    @GetMapping("/customers/{customerId}/restaurant-affinities")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public List<RestaurantAffinity> restaurantAffinities(
            @org.springframework.web.bind.annotation.PathVariable Long customerId,
            @RequestParam(defaultValue = "10") int limit) {
        String sql = """
            SELECT o.restaurant_id, COUNT(*) AS visits
            FROM orders o
            WHERE o.customer_id = ? AND o.status = 'DELIVERED'
            GROUP BY o.restaurant_id
            ORDER BY visits DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new RestaurantAffinity(rs.getLong("restaurant_id"), rs.getLong("visits")),
                customerId, clampLimit(limit));
    }

    /**
     * Items commonly bought alongside the given items, excluding anything the
     * customer has already had delivered.
     */
    @PostMapping("/customers/{customerId}/co-ordered")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public CoOrderedResponse coOrdered(
            @org.springframework.web.bind.annotation.PathVariable Long customerId,
            @RequestBody CoOrderedRequest request,
            @RequestParam(defaultValue = "10") int limit) {
        List<Long> itemIds = request == null ? null : request.itemIds();
        if (itemIds == null || itemIds.isEmpty()) {
            return new CoOrderedResponse(List.of());
        }
        String placeholders = String.join(",", itemIds.stream().map(id -> "?").toList());
        String sql = String.format("""
            SELECT co.menu_item_id, COUNT(*) AS co_occurrence
            FROM order_items oi
            JOIN orders o ON oi.order_id = o.id
            JOIN order_items co ON o.id = co.order_id AND co.menu_item_id <> oi.menu_item_id
            WHERE oi.menu_item_id IN (%s) AND o.status = 'DELIVERED'
              AND co.menu_item_id NOT IN (
                  SELECT oi2.menu_item_id FROM order_items oi2
                  JOIN orders o2 ON oi2.order_id = o2.id
                  WHERE o2.customer_id = ? AND o2.status = 'DELIVERED')
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
        params[idx] = clampLimit(limit);
        List<ItemFrequency> out = jdbcTemplate.query(sql,
                (rs, rowNum) -> new ItemFrequency(rs.getLong("menu_item_id"), rs.getLong("co_occurrence")),
                params);
        return new CoOrderedResponse(out);
    }

    public record CoOrderedRequest(List<Long> itemIds) {
    }

    /** Guard against a caller asking for an unbounded result set. */
    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return 10;
        }
        return Math.min(limit, 100);
    }
}
