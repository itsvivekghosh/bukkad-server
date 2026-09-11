package com.bhukkad.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
     * Optimistic lock (audit V-02 finish, mirrors WalletBalance/V-01). The
     * money path keeps its pessimistic FOR UPDATE read (adopted position,
     * docs §3.7); the version is the second fence: a stale managed write
     * outside the lock window now fails loudly instead of clobbering the row.
     */
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
