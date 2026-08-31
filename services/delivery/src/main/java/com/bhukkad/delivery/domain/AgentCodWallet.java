package com.bhukkad.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_cod_wallets", indexes = {
        @Index(name = "uk_agent_cod_wallet", columnList = "agentId", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class AgentCodWallet {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long agentId;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;
    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}