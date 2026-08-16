package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "membership_plans")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class MembershipPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Double pricePerMonth;

    @Column(nullable = false)
    private Boolean freeDelivery = true;

    @Column(nullable = false)
    private Double discountPercent = 0.0;

    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(name = "tier_level")
    private Integer tierLevel = 0; // 0=Basic, 1=Silver, 2=Gold, 3=Platinum

    @Column(name = "max_discount_percent")
    private Double maxDiscountPercent = 0.0;

    @Column(name = "referral_bonus_percent")
    private Double referralBonusPercent = 0.0;

    @Column(name = "referral_max_per_month")
    private Integer referralMaxPerMonth = 0;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}