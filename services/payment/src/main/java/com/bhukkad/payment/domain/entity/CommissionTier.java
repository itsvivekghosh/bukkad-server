package com.bhukkad.payment.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "commission_tiers")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class CommissionTier {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Integer minOrderCount;
    private Integer maxOrderCount;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionPct;
    @Column(nullable = false)
    private Boolean active = true;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}