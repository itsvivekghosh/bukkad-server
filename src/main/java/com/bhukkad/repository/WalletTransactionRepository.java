package com.bhukkad.repository;

import com.bhukkad.entity.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    Page<WalletTransaction> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);

    /**
     * Cursor-paginated wallet transaction list. The keyset predicate
     * ({@code (createdAt, id) < (cursorCreatedAt, cursorId)}) keeps query cost
     * O(page size) regardless of how deep the user scrolls, unlike
     * {@link #findByCustomerIdOrderByCreatedAtDesc(Long, Pageable)} which
     * degrades to O(offset) at large page indices.
     *
     * <p>The pair of nullable cursor parameters is supplied as {@code null} on
     * the first page (no cursor) — see {@code OrderRepository.findCustomer…
     * AfterCursor} for the same convention used elsewhere.
     */
    @Query("""
            SELECT w FROM WalletTransaction w
            WHERE w.customer.id = :customerId
            AND (:cursorCreatedAt IS NULL OR w.createdAt < :cursorCreatedAt
                 OR (w.createdAt = :cursorCreatedAt AND w.id < :cursorId))
            ORDER BY w.createdAt DESC, w.id DESC
            """)
    List<WalletTransaction> findByCustomerIdAfterCursor(
            @Param("customerId") Long customerId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    @Query("""
            SELECT COALESCE(SUM(w.amount), 0) FROM WalletTransaction w
            WHERE w.customer.id = :customerId AND w.type = :type
            """)
    double sumReferralCredits(@Param("customerId") Long customerId,
                              @Param("type") WalletTransaction.TransactionType type);
}
