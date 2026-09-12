package com.bhukkad.order.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.bhukkad.order.domain.entity.Coupon;

/** Coupon create/update request (extracted from monolith CouponRequest). */
public record CouponRequest(
        @NotBlank(message = "Coupon code is required")
        String code,

        @NotBlank(message = "Description is required")
        String description,

        @NotNull(message = "Discount type is required")
        String discountType,

        @NotNull(message = "Discount value is required")
        @Positive(message = "Discount must be positive")
        BigDecimal discountValue,

        BigDecimal minimumOrderAmount,

        BigDecimal maximumDiscountAmount,

        @NotNull(message = "Valid from date is required")
        LocalDateTime validFrom,

        @NotNull(message = "Valid until date is required")
        LocalDateTime validUntil,

        Integer usageLimit,

        Integer perUserLimit,

        Long restaurantId
) {
}