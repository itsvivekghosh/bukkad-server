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

    @Query("SELECT oi.menuItem.id, oi.menuItem.name, SUM(oi.quantity), SUM(oi.price * oi.quantity) " +
            "FROM OrderItem oi JOIN oi.order o " +
            "WHERE o.restaurant.id = :restaurantId AND o.status = 'DELIVERED' AND o.createdAt >= :startDate " +
            "GROUP BY oi.menuItem.id, oi.menuItem.name ORDER BY SUM(oi.quantity) DESC")
    List<Object[]> findTopSellingItems(
            @Param("restaurantId") Long restaurantId,
            @Param("startDate") LocalDateTime startDate);
}
