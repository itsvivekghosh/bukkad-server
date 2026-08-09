package com.bhukkad.repository;

import com.bhukkad.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderNumber(String orderNumber);

    List<Order> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Order> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId);

    List<Order> findByDeliveryAgentIdOrderByCreatedAtDesc(Long deliveryAgentId);

    @Query("SELECT o FROM Order o WHERE o.restaurant.id = :restaurantId AND " +
            "o.status = :status ORDER BY o.createdAt DESC")
    List<Order> findByRestaurantAndStatus(@Param("restaurantId") Long restaurantId,
                                          @Param("status") Order.OrderStatus status);

    @Query("SELECT o FROM Order o WHERE o.deliveryAgent.id = :agentId AND " +
            "o.status IN ('OUT_FOR_DELIVERY') ORDER BY o.createdAt DESC")
    List<Order> findActiveDeliveriesByAgent(@Param("agentId") Long agentId);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.customer.id = :customerId AND " +
            "o.createdAt >= :startDate")
    Long countCustomerOrdersSince(@Param("customerId") Long customerId,
                                  @Param("startDate") LocalDateTime startDate);
}