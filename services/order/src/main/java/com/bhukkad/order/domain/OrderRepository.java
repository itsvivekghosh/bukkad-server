package com.bhukkad.order.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByCustomerId(Long customerId);

    List<Order> findByCustomerIdAndStatus(Long customerId, String status);

    List<Order> findByRestaurantId(Long restaurantId);

    List<Order> findByStatus(String status);

    List<Order> findByStatusAndScheduledAtLessThanEqual(String status, LocalDateTime scheduledAt, Pageable pageable);

    Optional<Order> findByOrderNumber(String orderNumber);

    long countByCustomerId(Long customerId);

    long countByRestaurantId(Long restaurantId);

    long countByStatus(String status);

    long countByCustomerIdAndStatus(Long customerId, String status);

    @Query("SELECT COALESCE(SUM(o.totalAmount + COALESCE(o.walletAmountUsed, 0)), 0) FROM Order o " +
            "WHERE o.customerId = :customerId AND o.status = 'DELIVERED'")
    Double sumDeliveredSpendByCustomerId(@Param("customerId") Long customerId);

    @Query("SELECT COALESCE(SUM(o.totalAmount + o.walletAmountUsed), 0) FROM Order o " +
            "WHERE o.restaurantId = :restaurantId AND o.status = 'DELIVERED' AND o.createdAt >= :startDate")
    Double sumRestaurantRevenueSince(@Param("restaurantId") Long restaurantId,
                                     @Param("startDate") LocalDateTime startDate);

    /**
     * Moves up to {@code limit} orders older than {@code cutoff} into the
     * partitioned {@code orders_archive} and deletes them from the hot table.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            WITH moved AS (
                INSERT INTO orders_archive
                    (id, customer_id, restaurant_id, status, total_amount, currency, created_at, updated_at)
                SELECT id, customer_id, restaurant_id, status, total_amount, currency, created_at, updated_at
                FROM orders
                WHERE created_at < CAST(:cutoff AS timestamp)
                LIMIT :limit
                RETURNING id
            )
            DELETE FROM orders WHERE id IN (SELECT id FROM moved)
            """, nativeQuery = true)
    int archiveOldOrders(@Param("cutoff") LocalDate cutoff, @Param("limit") int limit);
}
