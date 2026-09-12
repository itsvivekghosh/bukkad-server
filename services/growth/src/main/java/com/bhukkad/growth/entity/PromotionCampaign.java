package com.bhukkad.growth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Back-office promotion campaign entity for the V1 baseline
 * {@code promotion_campaigns} table (previously unmapped).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "promotion_campaigns")
public class PromotionCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "campaign_type", length = 50)
    private String campaignType;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "discount_percent", precision = 5, scale = 2)
    private java.math.BigDecimal discountPercent;

    @Column(name = "flat_discount_amount", precision = 10, scale = 2)
    private java.math.BigDecimal flatDiscountAmount;

    @Column(name = "min_order_amount", precision = 10, scale = 2)
    private java.math.BigDecimal minOrderAmount;

    @Column(name = "max_discount_amount", precision = 10, scale = 2)
    private java.math.BigDecimal maxDiscountAmount;

    @Column(name = "free_delivery")
    private boolean freeDelivery;

    @Column(name = "buy_quantity")
    private Integer buyQuantity;

    @Column(name = "get_quantity")
    private Integer getQuantity;

    @Column(name = "get_discount_percent")
    private Integer getDiscountPercent;

    @Column(length = 50)
    private String targetSegment;

    private int priority;

    @Column(name = "is_active")
    private boolean active;

    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "buy_quantity")
    private Integer buyQuantity;

    @Column(name = "get_quantity")
    private Integer getQuantity;

    @Column(name = "get_discount_percent")
    private Integer getDiscountPercent;

    @Column(name = "target_segment", length = 50)
    private String targetSegment;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void stampCreated() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void stampUpdated() {
        updatedAt = LocalDateTime.now();
    }
}
