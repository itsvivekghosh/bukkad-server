package com.bhukkad.dto.request;

import lombok.Data;

import java.time.LocalDateTime;

/** Admin request to create or update a promotion campaign. */
@Data
public class PromotionCampaignRequest {
    private String name;
    private String campaignType;
    private String description;
    private Double discountPercent;
    private Double flatDiscountAmount;
    private Double minOrderAmount;
    private Double maxDiscountAmount;
    private Long restaurantId;
    private Boolean freeDelivery;
    private Integer priority;
    private Integer usageLimit;
    private Integer perUserLimit;
    private Boolean isActive;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;

    /** Buy-X-Get-Y: quantity the customer must buy to unlock the offer. */
    private Integer buyQuantity;

    /** Buy-X-Get-Y: quantity granted (discounted) when buyQuantity is met. */
    private Integer getQuantity;

    /** Buy-X-Get-Y: discount percent on the "get" items (100 = free). */
    private Double getDiscountPercent;

    /** Target user segment: ALL, NEW_USER, VIP. */
    private String targetSegment;

    /** When set, Buy-X-Get-Y applies only to this menu item. */
    private Long applicableMenuItemId;
}
