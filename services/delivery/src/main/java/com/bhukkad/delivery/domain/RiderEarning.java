package com.bhukkad.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "rider_earnings", indexes = {@Index(name = "idx_rider_earnings_agent", columnList = "agentId")})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class RiderEarning {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long agentId;
    @Column(nullable = false)
    private Long orderId;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, length = 20)
    private String status;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}