package com.bhukkad.recommendation;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native read-model queries backing the personalization engine (FEATURE #4).
 *
 * <p>Deliberately self-contained: these analytical queries do not belong on the
 * transactional {@code OrderItemRepository}, and a dedicated component keeps the
 * hot order repositories free of reporting methods.</p>
 *
 * <p>All signals are computed from DELIVERED orders only — completed purchases are
 * the only trustworthy "customer liked this" signal.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationQueryService {

    private static final String PARAM_LIMIT = "limit";


    @PersistenceContext
    private final EntityManager entityManager;

    /**
     * The customer's most frequently ordered items, newest first.
     * Returns rows of {@code [menuItemId, totalQuantity]}.
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> findCustomerItemFrequencies(Long customerId, int limit) {
        return entityManager.createNativeQuery("""
                SELECT oi.menu_item_id, SUM(oi.quantity) AS total_qty, MAX(o.created_at) AS last_ordered
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                WHERE o.customer_id = :customerId AND o.status = 'DELIVERED'
                GROUP BY oi.menu_item_id
                ORDER BY total_qty DESC, last_ordered DESC
                LIMIT :limit
                """)
                .setParameter("customerId", customerId)
                .setParameter(PARAM_LIMIT, limit)
                .getResultList();
    }

    /**
     * Collaborative filtering signal: items most frequently co-ordered with any of
     * the given items across all other customers. Returns rows of
     * {@code [menuItemId, frequency]}.
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> findCoOrderedItems(List<Long> menuItemIds, Long excludeCustomerId, int limit) {
        if (menuItemIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createNativeQuery("""
                SELECT oi2.menu_item_id, COUNT(DISTINCT o.id) AS freq
                FROM order_items oi1
                JOIN orders o ON o.id = oi1.order_id
                JOIN order_items oi2 ON oi2.order_id = o.id AND oi2.menu_item_id <> oi1.menu_item_id
                WHERE oi1.menu_item_id IN (:menuItemIds)
                  AND (:excludeCustomerId IS NULL OR o.customer_id <> :excludeCustomerId)
                  AND o.status = 'DELIVERED'
                GROUP BY oi2.menu_item_id
                ORDER BY freq DESC
                LIMIT :limit
                """)
                .setParameter("menuItemIds", menuItemIds)
                .setParameter("excludeCustomerId", excludeCustomerId)
                .setParameter(PARAM_LIMIT, limit)
                .getResultList();
    }

    /** Distinct restaurant ids the customer has ordered from, by frequency. */
    @SuppressWarnings("unchecked")
    public List<Object[]> findCustomerRestaurantAffinities(Long customerId, int limit) {
        return entityManager.createNativeQuery("""
                SELECT o.restaurant_id, COUNT(*) AS order_count
                FROM orders o
                WHERE o.customer_id = :customerId AND o.status = 'DELIVERED'
                  AND o.restaurant_id IS NOT NULL
                GROUP BY o.restaurant_id
                ORDER BY order_count DESC
                LIMIT :limit
                """)
                .setParameter("customerId", customerId)
                .setParameter(PARAM_LIMIT, limit)
                .getResultList();
    }

    /**
     * Hydrates menu items preserving the supplied id order and keeping only
     * currently available ones.
     */
    public List<com.bhukkad.entity.MenuItem> hydrateAvailableItems(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // findAllByIdsWithDetails does not preserve order — re-map explicitly.
        Map<Long, com.bhukkad.entity.MenuItem> byId = new LinkedHashMap<>();
        for (Object row : entityManager.createQuery(
                        "SELECT m FROM MenuItem m LEFT JOIN FETCH m.category WHERE m.id IN (:ids)")
                .setParameter("ids", ids)
                .getResultList()) {
            com.bhukkad.entity.MenuItem item = (com.bhukkad.entity.MenuItem) row;
            byId.put(item.getId(), item);
        }
        List<com.bhukkad.entity.MenuItem> result = new ArrayList<>(ids.size());
        Set<Long> seen = new HashSet<>();
        for (Long id : ids) {
            com.bhukkad.entity.MenuItem item = byId.get(id);
            if (item != null && Boolean.TRUE.equals(item.getAvailable()) && seen.add(id)) {
                result.add(item);
            }
        }
        return result;
    }
}
