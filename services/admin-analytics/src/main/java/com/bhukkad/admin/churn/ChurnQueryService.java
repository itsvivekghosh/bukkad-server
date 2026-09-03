package com.bhukkad.admin.churn;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-model queries for churn feature engineering (FEATURE #12).
 * Self-contained like {@code RecommendationQueryService}: analytical aggregates
 * stay off the transactional order repository.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ChurnQueryService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Per-customer behavioural aggregates over delivered orders:
     * {@code [customerId, lastOrderAt, totalOrders, recentOrders, previousPeriodOrders,
     *         cancelledOrders, couponOrders]}.
     *
     * <p>{@code recent} = window ending now; {@code previous} = the equally sized
     * window before it. The ratio between the two is the frequency-decay signal.</p>
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> customerOrderAggregates(List<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime recentStart = now.minusDays(30);
        LocalDateTime previousStart = now.minusDays(60);

        return entityManager.createNativeQuery("""
                SELECT o.customer_id,
                       MAX(o.created_at) AS last_order_at,
                       COUNT(*) AS total_orders,
                       SUM(CASE WHEN o.created_at >= :recentStart THEN 1 ELSE 0 END) AS recent_orders,
                       SUM(CASE WHEN o.created_at >= :previousStart AND o.created_at < :recentStart THEN 1 ELSE 0 END) AS prev_orders,
                       SUM(CASE WHEN o.status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled_orders,
                       SUM(CASE WHEN o.coupon_id IS NOT NULL THEN 1 ELSE 0 END) AS coupon_orders
                FROM orders o
                WHERE o.customer_id IN (:customerIds)
                  AND o.status <> 'PENDING'
                GROUP BY o.customer_id
                """)
                .setParameter("customerIds", customerIds)
                .setParameter("recentStart", recentStart)
                .setParameter("previousStart", previousStart)
                .getResultList();
    }

    /** Ids of active customers eligible for scoring (bounded batch). */
    @SuppressWarnings("unchecked")
    public List<Long> findScorableCustomerIds(int limit) {
        return entityManager.createNativeQuery("""
                SELECT u.id FROM users u
                WHERE u.active = 1 AND u.role = 'CUSTOMER'
                ORDER BY u.id
                LIMIT :limit
                """)
                .setParameter("limit", limit)
                .getResultList();
    }
}
