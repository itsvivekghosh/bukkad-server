package com.bhukkad.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByCustomerId(Long customerId);

    /**
     * Moves up to {@code limit} orders older than {@code cutoff} into the
     * partitioned {@code orders_archive} and deletes them from the hot table
     * (PG port of the monolith's MySQL archive job — MySQL used
     * {@code INSERT ... SELECT ... LIMIT} + {@code DELETE ... LIMIT}, which PG
     * does not allow; a data-modifying CTE is the equivalent).
     *
     * @return number of archived orders
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