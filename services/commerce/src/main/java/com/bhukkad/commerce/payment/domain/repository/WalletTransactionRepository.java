package com.bhukkad.commerce.payment.domain.repository;

import com.bhukkad.commerce.payment.domain.entity.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Page<WalletTransaction> findByCustomerId(Long customerId, Pageable pageable);

    /**
     * @deprecated Use {@link #findByCustomerId(Long, Pageable)} instead for bounded results.
     */
    @Deprecated
    List<WalletTransaction> findByCustomerId(Long customerId);

    /**
     * @deprecated Use {@link #findByCustomerId(Long, Pageable)} instead.
     */
    @Deprecated
    @Query("SELECT wt FROM WalletTransaction wt WHERE wt.customerId = :customerId ORDER BY wt.createdAt DESC")
    Page<WalletTransaction> pageNewestFirst(Long customerId, Pageable pageable);

    @Query("SELECT wt FROM WalletTransaction wt WHERE wt.customerId = :customerId ORDER BY wt.createdAt DESC")
    Page<WalletTransaction> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);

    @Query("SELECT wt FROM WalletTransaction wt WHERE wt.customerId = :customerId AND wt.createdAt < :before ORDER BY wt.createdAt DESC")
    Page<WalletTransaction> findByCustomerIdAndCreatedAtBefore(Long customerId, LocalDateTime before, Pageable pageable);

    @Query("SELECT wt FROM WalletTransaction wt WHERE wt.customerId = :customerId AND (wt.createdAt < :createdAt OR (wt.createdAt = :createdAt AND wt.id < :id)) ORDER BY wt.createdAt DESC, wt.id DESC")
    Page<WalletTransaction> afterCursor(Long customerId, LocalDateTime createdAt, Long id, Pageable pageable);
}
