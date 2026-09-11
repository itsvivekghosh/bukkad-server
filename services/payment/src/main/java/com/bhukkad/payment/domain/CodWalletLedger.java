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
 * Append-only audit ledger for rider COD wallet movements (audit V-02).
 *
 * <p>One row per balance mutation, written inside the SAME transaction as the
 * {@link AgentCodWallet} update by {@code CodWalletService}: the balance and
 * its evidence can never diverge. Rows are never updated or deleted —
 * reconciliation replays the (agent_id, created_at) sequence and checks that
 * {@code balance_after} is consistent with the amounts.</p>
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

    /** Owning delivery order when the movement is order-bound; else null. */
    @Column(name = "order_id")
    private Long orderId;

    /** CREDIT or DEBIT (VARCHAR(6) domain). */
    @Column(nullable = false, length = 6)
    private String type;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** The wallet balance AS PERSISTED by this movement. */
    @Column(name = "balance_after", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
