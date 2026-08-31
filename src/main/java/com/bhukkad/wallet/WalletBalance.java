package com.bhukkad.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * The wallet domain's authoritative balance row, one per customer.
 *
 * <p>Phase 2 data ownership: concurrent debits/credits serialise on THIS row
 * (pessimistic write lock via {@code WalletBalanceRepository}), not on the
 * identity domain's customer row. {@code customers.wallet_balance} remains a
 * synchronised read-model during the transition.</p>
 */
@Entity
@Table(name = "wallet_balances")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class WalletBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, unique = true)
    private Long customerId;

    @Builder.Default
    @Column(nullable = false)
    private Double balance = 0.0;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
