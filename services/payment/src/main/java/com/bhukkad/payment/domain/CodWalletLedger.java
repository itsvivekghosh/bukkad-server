package com.bhukkad.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Append-only COD wallet ledger (audit V-02 finish): one row per committed
 * balance mutation on {@link AgentCodWallet}, written inside the SAME
 * transaction as the mutation so the audit trail can never lag or lead the
 * balance. {@code balanceAfter} carries the post-mutation persisted balance,
 * making the per-agent sequence a monotonic audit chain for reconciliation.
 */
@Entity
@Table(name = "cod_wallet_ledger", indexes = {
        @Index(name = "idx_cod_ledger_agent", columnList = "agent_id, created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class CodWalletLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    /** Order the movement belongs to, when one exists (direct adjustments may be null). */
    @Column(name = "order_id")
    private Long orderId;

    @Column(nullable = false, length = 6)
    private String type; // CREDIT, DEBIT

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
