package com.bhukkad.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Live rider COD wallet. Balance mutations serialize on the pessimistic
 * {@code findByAgentIdForUpdate} read; {@code version} (@Version, runbook
 * step 2) additionally makes any stale detached write fail as an
 * {@code OptimisticLockException} instead of clobbering the row.
 */
@Entity
@Table(name = "agent_cod_wallets", indexes = {
        @Index(name = "uk_agent_cod_wallet", columnList = "agent_id", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class AgentCodWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    /**
     * Optimistic-lock anchor (V11 migration): the FOR UPDATE lock stays the
     * primary serialization mechanism; this only catches writes that bypassed
     * the lock window.
     */
    @Version
    private Long version;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
