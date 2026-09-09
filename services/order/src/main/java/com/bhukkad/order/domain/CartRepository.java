package com.bhukkad.order.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByCustomerId(Long customerId);

    Optional<Cart> findByCustomerIdAndStatus(Long customerId, String status);

    /**
     * PERF-3 (V-carts): SQL-predicated stale-cart sweep read. Replaces the
     * {@code findAll().stream().filter(...)} scan of CartRecoveryService: the
     * predicate (status + updated_at cutoff) and the ORDER BY run in the
     * database, bounded by the pageable batch size, and only ids are loaded.
     * Backed by the composite (status, updated_at) index (V8 migration).
     */
    @Query("""
            SELECT c.id FROM Cart c
            WHERE c.status = :status AND c.updatedAt < :cutoff
            ORDER BY c.id
            """)
    List<Long> findIdsByStatusAndUpdatedAtBefore(@Param("status") String status,
                                                 @Param("cutoff") LocalDateTime cutoff,
                                                 Pageable pageable);

    /** Bulk ACTIVE→:status flip for one sweep batch (statement, not per-row saves). */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("""
            UPDATE Cart c SET c.status = :status, c.updatedAt = :now
            WHERE c.id IN :ids
            """)
    int updateStatusByIds(@Param("ids") List<Long> ids,
                          @Param("status") String status,
                          @Param("now") LocalDateTime now);
}
