package com.bhukkad.repository;

import com.bhukkad.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant " +
            "JOIN FETCH o.deliveryAddress " +
            "WHERE o.id = :id")
    Optional<Order> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant " +
            "JOIN FETCH o.deliveryAddress " +
            "WHERE o.customer.id = :customerId " +
            "ORDER BY o.createdAt DESC")
    List<Order> findByCustomerIdWithDetails(@Param("customerId") Long customerId);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant " +
            "JOIN FETCH o.deliveryAddress " +
            "WHERE o.restaurant.id = :restaurantId " +
            "ORDER BY o.createdAt DESC")
    List<Order> findByRestaurantIdWithDetails(@Param("restaurantId") Long restaurantId);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant " +
            "JOIN FETCH o.deliveryAddress " +
            "WHERE o.restaurant.id = :restaurantId AND o.status = :status " +
            "ORDER BY o.createdAt DESC")
    List<Order> findByRestaurantAndStatusWithDetails(@Param("restaurantId") Long restaurantId, @Param("status") Order.OrderStatus status);

    long countByCustomerId(Long customerId);

    // Add missing methods for Admin Service
    long countByCreatedAtAfter(LocalDateTime dateTime);
    long countByStatus(Order.OrderStatus status);
    long countByStatusAndCreatedAtAfter(Order.OrderStatus status, LocalDateTime dateTime);

    @Query("SELECT SUM(o.totalAmount) FROM Order o WHERE o.status = 'DELIVERED'")
    Double sumTotalAmount();

    @Query("SELECT SUM(o.totalAmount) FROM Order o WHERE o.status = 'DELIVERED' AND o.createdAt >= :startDate")
    Double sumTotalAmountAfter(@Param("startDate") LocalDateTime startDate);

    List<Order> findTop10ByOrderByCreatedAtDesc();
    Page<Order> findByStatus(Order.OrderStatus status, Pageable pageable);
}