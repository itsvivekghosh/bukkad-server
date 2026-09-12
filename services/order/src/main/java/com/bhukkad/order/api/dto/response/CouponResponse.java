package com.bhukkad.order.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.bhukkad.order.domain.entity.Coupon;

/** Coupon response (extracted from monolith CouponResponse). */
public record CouponResponse(
        Long id,
        String code,
        String description,
        String discountType,
        BigDecimal discountValue,
        BigDecimal minimumOrderAmount,
        BigDecimal maximumDiscountAmount,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        Integer usageLimit,
        Integer usedCount,
        Integer perUserLimit,
        Boolean active,
        Long restaurantId
) {
}