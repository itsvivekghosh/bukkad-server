package com.bhukkad.repository;

import com.bhukkad.entity.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    Page<WalletTransaction> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);

    @Query("""
            SELECT COALESCE(SUM(w.amount), 0) FROM WalletTransaction w
            WHERE w.customer.id = :customerId AND w.type = :type
            """)
    double sumReferralCredits(@Param("customerId") Long customerId,
                              @Param("type") WalletTransaction.TransactionType type);
}
