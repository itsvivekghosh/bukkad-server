package com.bhukkad.restaurant.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "promotion_campaigns")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class PromotionCampaign {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String name;
    private String description;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPct;
    @Column(precision = 10, scale = 2)
    private BigDecimal maxDiscount;
    @Column(nullable = false)
    private LocalDateTime startsAt;
    @Column(nullable = false)
    private LocalDateTime endsAt;
    @Column(nullable = false)
    private Boolean active = true;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}