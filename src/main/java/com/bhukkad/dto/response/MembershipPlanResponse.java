package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MembershipPlanResponse {
    private Long id;
    private String name;
    private String description;
    private Double pricePerMonth;
    private Boolean freeDelivery;
    private Double discountPercent;
    private Integer tierLevel;
    private Double maxDiscountPercent;
    private Double referralBonusPercent;
    private Integer referralMaxPerMonth;
}
