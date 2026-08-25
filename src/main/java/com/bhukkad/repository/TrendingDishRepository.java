package com.bhukkad.repository;

import com.bhukkad.entity.TrendingDish;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persistence for the ANALYTICS-owned trending_dishes summary table.
 * All writes are upserts from the ORDER_ITEMS_SNAPSHOT outbox event; reads
 * are the hot home-feed path and are routed to the read replica.
 */
@Repository
public interface TrendingDishRepository extends JpaRepository<TrendingDish, Long> {

    @Modifying
    @Query(value = "INSERT INTO trending_dishes " +
            "(menu_item_id, restaurant_id, dish_name, quantity_sold, last_order_at) " +
            "VALUES (:menuItemId, :restaurantId, :dishName, :quantity, :orderedAt) " +
            "ON DUPLICATE KEY UPDATE quantity_sold = quantity_sold + :quantity, " +
            "last_order_at = GREATEST(last_order_at, :orderedAt), " +
            "dish_name = VALUES(dish_name)",
            nativeQuery = true)
    int upsert(@Param("menuItemId") Long menuItemId,
               @Param("restaurantId") Long restaurantId,
               @Param("dishName") String dishName,
               @Param("quantity") long quantity,
               @Param("orderedAt") LocalDateTime orderedAt);

    @Query("SELECT td FROM TrendingDish td ORDER BY td.quantitySold DESC")
    List<TrendingDish> findTopByQuantitySoldDesc(org.springframework.data.domain.Pageable pageable);
}
