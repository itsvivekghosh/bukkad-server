package com.bhukkad.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import com.bhukkad.order.domain.entity.GiftCard;

public interface GiftCardRepository extends JpaRepository<GiftCard, Long> {
    Optional<GiftCard> findByCodeAndStatus(String code, String status);

    Optional<GiftCard> findByCode(String code);

    /** Gift cards purchased by a customer (self-service "my cards" view). */
    List<GiftCard> findByPurchasedByOrderByCreatedAtDesc(Long purchasedBy);

    /** Gift cards bought for a recipient email (self-service "received" view). */
    List<GiftCard> findByRecipientEmailOrderByCreatedAtDesc(String recipientEmail);

    /**
     * Atomic conditional redemption: decrements only when the balance covers
     * the amount, so concurrent redemptions cannot both pass a check-then-act
     * gap and overdraw. Returns the number of affected rows (0 = insufficient
     * balance or card no longer active).
     */
    @Modifying
    @Query("UPDATE GiftCard g SET g.balance = g.balance - :amount, g.redeemedBy = :redeemedBy, " +
            "g.redeemedAt = CURRENT_TIMESTAMP " +
            "WHERE g.code = :code AND g.status = 'ACTIVE' AND g.balance >= :amount")
    int redeem(@Param("code") String code,
               @Param("amount") BigDecimal amount,
               @Param("redeemedBy") Long redeemedBy);

    @Modifying
    @Query("UPDATE GiftCard g SET g.status = :status WHERE g.code = :code")
    int updateStatus(@Param("code") String code, @Param("status") String status);
}
