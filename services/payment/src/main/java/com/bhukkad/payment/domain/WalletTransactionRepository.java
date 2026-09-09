package com.bhukkad.payment.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    List<WalletTransaction> findByCustomerId(Long customerId);

    /** Newest-first page (offset pagination). */
    @Query("SELECT t FROM WalletTransaction t WHERE t.customerId = :customerId " +
            "ORDER BY t.createdAt DESC, t.id DESC")
    List<WalletTransaction> pageNewestFirst(@Param("customerId") Long customerId, Pageable pageable);

    /**
     * Keyset-ordered newest-first page for cursor pagination — the keyset
     * predicate keeps query cost constant regardless of scroll depth.
     */
    @Query("SELECT t FROM WalletTransaction t WHERE t.customerId = :customerId " +
            "AND (t.createdAt < :cursorCreatedAt " +
            "OR (t.createdAt = :cursorCreatedAt AND t.id < :cursorId)) " +
            "ORDER BY t.createdAt DESC, t.id DESC")
    List<WalletTransaction> afterCursor(@Param("customerId") Long customerId,
                                        @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                        @Param("cursorId") Long cursorId,
                                        Pageable pageable);
}
