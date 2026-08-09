package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "coupons", indexes = {
        @Index(name = "idx_coupon_code", columnList = "code", unique = true),
        @Index(name = "idx_coupon_restaurant", columnList = "restaurant_id"),
        @Index(name = "idx_coupon_active", columnList = "active"),
        @Index(name = "idx_coupon_valid_from", columnList = "validFrom"),
        @Index(name = "idx_coupon_valid_until", columnList = "validUntil"),
        @Index(name = "idx_coupon_active_valid", columnList = "active, validFrom, validUntil")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiscountType discountType;

    @Column(nullable = false)
    private Double discountValue;

    private Double minimumOrderAmount;

    private Double maximumDiscountAmount;

    @Column(nullable = false)
    private LocalDateTime validFrom;

    @Column(nullable = false)
    private LocalDateTime validUntil;

    private Integer usageLimit;

    private Integer usedCount = 0;

    private Integer perUserLimit;

    @Column(nullable = false)
    private Boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id")
    private Restaurant restaurant; // null for platform-wide coupons

    public enum DiscountType {
        PERCENTAGE, FIXED_AMOUNT
    }
}