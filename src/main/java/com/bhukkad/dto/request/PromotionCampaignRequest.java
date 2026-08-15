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
}
