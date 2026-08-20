package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PromotionCampaignResponse {
    private Long id;
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
    private String startsAt;
    private String endsAt;
    private Integer buyQuantity;
    private Integer getQuantity;
    private Double getDiscountPercent;
    private String targetSegment;
    private Long applicableMenuItemId;
}
