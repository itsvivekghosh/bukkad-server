package com.bhukkad.restaurant.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coupon_usages", indexes = {
        @Index(name = "idx_coupon_usages_customer", columnList = "customerId"),
        @Index(name = "uk_coupon_usage", columnList = "couponId, customerId, orderId", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class CouponUsage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long couponId;
    @Column(nullable = false)
    private Long customerId;
    private Long orderId;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}