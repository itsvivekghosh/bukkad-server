package com.bhukkad.order.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Coupon extracted from the monolith (com.bhukkad.entity.Coupon).
 *
 * <p>Discount rules that can be applied to orders. A coupon may be platform-wide
 * ({@code restaurantId == null}) or restricted to a specific restaurant.</p>
 */
@Entity
@Table(name = "coupons", indexes = {
        @Index(name = "idx_coupon_restaurant", columnList = "restaurant_id"),
        @Index(name = "idx_coupon_active_valid", columnList = "active, validFrom, validUntil")
})
@Getter
@Setter
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscountType discountType;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(precision = 10, scale = 2)
    private BigDecimal minimumOrderAmount;

    @Column(precision = 10, scale = 2)
    private BigDecimal maximumDiscountAmount;

    @Column(nullable = false)
    private LocalDateTime validFrom;

    @Column(nullable = false)
    private LocalDateTime validUntil;

    private Integer usageLimit;

    @Column(nullable = false)
    private Integer usedCount = 0;

    private Integer perUserLimit;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "restaurant_id")
    private Long restaurantId;

    public enum DiscountType {
        PERCENTAGE, FIXED_AMOUNT
    }
}