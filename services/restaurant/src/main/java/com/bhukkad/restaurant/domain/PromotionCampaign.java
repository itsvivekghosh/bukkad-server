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

    /** Campaign discriminator (PERCENTAGE, FLAT, FREE_DELIVERY, BUY_X_GET_Y). */
    @Column(nullable = false, length = 30)
    private String campaignType = "PERCENTAGE";

    /** Percent discount used by the ported monolith promotion engine. */
    private Double discountPercent;

    private Double minOrderAmount;

    /** Owning restaurant (scalar FK; null = all restaurants). */
    private Long restaurantId;

    private Double maxDiscountAmount;

    private Double flatDiscountAmount;

    @Column(nullable = false)
    private Boolean freeDelivery = false;

    @Column(nullable = false)
    private Integer priority = 0;

    private Integer usageLimit;

    private Integer perUserLimit = 1;

    /** Buy-X-Get-Y: items the customer must purchase to unlock the offer. */
    private Integer buyQuantity;

    /** Buy-X-Get-Y: items granted (discounted) when buyQuantity is met. */
    private Integer getQuantity;

    /** Buy-X-Get-Y: discount percent applied to the "get" items (100 = free). */
    private Double getDiscountPercent;

    /** User segment this campaign targets; null/ALL applies to everyone. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private CampaignSegment targetSegment;

    /** When set, the Buy-X-Get-Y offer applies only to this menu item (scalar FK). */
    private Long applicableMenuItemId;

    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum CampaignSegment {
        ALL,
        NEW_USER,
        VIP
    }
}