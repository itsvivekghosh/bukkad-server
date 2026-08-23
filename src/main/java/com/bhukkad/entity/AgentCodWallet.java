package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/** Cash-on-delivery wallet for a delivery agent (collection/deposit/reconciliation). */
@Entity
@Table(name = "agent_cod_wallets", indexes = {
        @Index(name = "uk_agent_cod_wallet_agent", columnList = "agent_id", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class AgentCodWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(nullable = false)
    private Double totalCashCollected = 0.0;

    @Column(nullable = false)
    private Double totalCashDeposited = 0.0;

    private LocalDateTime lastReconciledAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Outstanding cash the agent owes the platform: collected minus deposited. */
    public double getBalance() {
        return totalCashCollected - totalCashDeposited;
    }
}
