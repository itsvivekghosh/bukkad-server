package com.bhukkad.growth.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignResponse {
    private Long id;
    private String name;
    private String campaignType;
    private String description;
    private Double discountPercent;
    private Double flatDiscountAmount;
    private Double minOrderAmount;
    private Double maxDiscountAmount;
    private Boolean freeDelivery;
    private Integer priority;
    private Boolean isActive;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private Integer buyQuantity;
    private Integer getQuantity;
    private Integer getDiscountPercent;
    private String targetSegment;
}
