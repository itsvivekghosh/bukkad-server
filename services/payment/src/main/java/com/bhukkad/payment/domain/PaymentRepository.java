package com.bhukkad.payment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
    Optional<Payment> findByProviderRef(String providerRef);
    Optional<Payment> findByTransactionId(String transactionId);
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    Optional<Payment> findByGatewayOrderId(String gatewayOrderId);
    List<Payment> findByCustomerId(Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdWithLock(@Param("id") Long id);

    /**
     * Legal-transition guard for the webhook path (audit feature #1): flips the
     * payment only from the expected source status in ONE conditional statement,
     * so a concurrent settle/refund loses with 0 rows instead of double-moving.
     * Returns the number of rows updated; 0 = illegal/lost transition.
     */
    @Modifying
    @Query(value = """
            UPDATE payments
            SET status = :to,
                completed_at = localtimestamp,
                updated_at = localtimestamp
            WHERE id = :id AND status = :from
            """, nativeQuery = true)
    int transitionStatus(@Param("id") Long id, @Param("from") String from, @Param("to") String to);

    /** Conditional PENDING → FAILED flip used when the PSP charge is declined. */
    @Modifying
    @Query(value = """
            UPDATE payments
            SET status = 'FAILED', updated_at = localtimestamp
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markFailedIfPending(@Param("id") Long id);
}