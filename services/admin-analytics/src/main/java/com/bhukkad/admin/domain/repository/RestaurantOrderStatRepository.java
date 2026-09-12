package com.bhukkad.admin.domain.repository;
import com.bhukkad.admin.domain.entity.RestaurantOrderStat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface RestaurantOrderStatRepository extends JpaRepository<RestaurantOrderStat, Long> {

    /**
     * Atomic CQRS projection increment (PERF-2/V-12). The old
     * findById → +1 → save was a lost-update race: two concurrent
     * {@code OrderCreated} events for one restaurant dropped each other's
     * deltas. {@code INSERT … ON CONFLICT (restaurant_id) DO UPDATE} applies
     * count and revenue in one serialized statement (conflict target backed by
     * the PK — plus the explicit guard migrated in V9).
     *
     * <p>Native SQL (PostgreSQL dialect, consistent with the V-* baselines).
     * {@code localtimestamp} keeps the column type aligned with the
     * {@code TIMESTAMP(6)} without time zone convention (plan §5.4).</p>
     */
    @Modifying
    @Query(value = """
            INSERT INTO restaurant_order_stats (restaurant_id, order_count, revenue, updated_at)
            VALUES (:restaurantId, 1, CAST(:total AS numeric), localtimestamp)
            ON CONFLICT (restaurant_id) DO UPDATE SET
                order_count = restaurant_order_stats.order_count + 1,
                revenue = restaurant_order_stats.revenue + CAST(:total AS numeric),
                updated_at = localtimestamp
            """, nativeQuery = true)
    int upsertIncrement(@Param("restaurantId") long restaurantId,
                        @Param("total") BigDecimal total);
}
