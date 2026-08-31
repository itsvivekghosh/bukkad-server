package com.bhukkad.restaurant.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coupons", indexes = {
        @Index(name = "uk_coupon_code", columnList = "code", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class Coupon {
    public static final String DISCOUNT_PERCENT = "PERCENT";
    public static final String DISCOUNT_FIXED = "FIXED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40)
    private String code;
    @Column(nullable = false, length = 20)
    private String discountType;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal minOrderAmount = BigDecimal.ZERO;
    @Column(precision = 10, scale = 2)
    private BigDecimal maxDiscount;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    @Column(nullable = false)
    private Boolean active = true;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}