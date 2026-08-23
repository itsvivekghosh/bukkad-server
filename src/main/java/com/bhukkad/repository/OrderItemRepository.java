package com.bhukkad.repository;

import com.bhukkad.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("SELECT COUNT(oi) FROM OrderItem oi WHERE oi.menuItem.id = :menuItemId")
    long countByMenuItemId(@Param("menuItemId") Long menuItemId);

    @Query("SELECT oi.menuItem.id, oi.menuItem.name, SUM(oi.quantity), SUM(oi.price * oi.quantity) " +
            "FROM OrderItem oi JOIN oi.order o " +
            "WHERE o.restaurant.id = :restaurantId AND o.status = 'DELIVERED' AND o.createdAt >= :startDate " +
            "GROUP BY oi.menuItem.id, oi.menuItem.name ORDER BY SUM(oi.quantity) DESC")
    List<Object[]> findTopSellingItems(
            @Param("restaurantId") Long restaurantId,
            @Param("startDate") LocalDateTime startDate);

    /** Counts order-item quantities per menu item since a timestamp (trending dishes). */
    @Query("SELECT oi.menuItem.id, oi.menuItem.name, SUM(oi.quantity) AS qty " +
           "FROM OrderItem oi WHERE oi.order.createdAt >= :since " +
           "GROUP BY oi.menuItem.id, oi.menuItem.name ORDER BY qty DESC")
    List<Object[]> findTrendingByCreatedSince(@Param("since") java.time.LocalDateTime since);
}
