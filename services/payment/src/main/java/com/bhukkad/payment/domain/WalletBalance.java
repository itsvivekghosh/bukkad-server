package com.bhukkad.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "wallet_balances")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class WalletBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    /**
     * Optimistic lock (audit V-01 finish). The money path keeps its
     * pessimistic FOR UPDATE read (adopted position, docs §3.7); the version
     * is the second fence: a stale managed write outside the lock window now
     * fails loudly instead of clobbering the row.
     */
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
