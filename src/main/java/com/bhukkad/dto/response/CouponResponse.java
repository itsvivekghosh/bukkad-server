package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponResponse {
    private Long id;
    private String code;
    private String description;
    private String discountType;
    private Double discountValue;
    private Double minimumOrderAmount;
    private Double maximumDiscountAmount;
    private String validFrom;
    private String validUntil;
    private Integer usageLimit;
    private Integer usedCount;
    private Integer perUserLimit;
    private Boolean active;
    private Long restaurantId;
    private String restaurantName;
}