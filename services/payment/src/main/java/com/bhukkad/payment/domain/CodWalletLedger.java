package com.bhukkad.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Append-only ledger entry for an agent COD wallet mutation (audit V-02
 * finish). One row per committed CREDIT/DEBIT, written in the SAME
 * transaction as the balance change by {@code CodWalletService};
 * {@code balanceAfter} is the balance AS PERSISTED (M-1 rule from V-01),
 * never a recomputed in-memory sum. Never update or delete rows.
 */
@Entity
@Table(name = "cod_wallet_ledger", indexes = {
        @Index(name = "idx_cod_ledger_agent_created", columnList = "agent_id, created_at")
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

    /** Optional order attribution (nullable — the amount-only contract stands). */
    @Column(name = "order_id")
    private Long orderId;

    /** {@code CREDIT} or {@code DEBIT}; VARCHAR(6) in the schema. */
    @Column(nullable = false, length = 6)
    private String type;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
