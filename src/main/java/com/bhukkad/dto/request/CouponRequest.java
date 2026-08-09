package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CouponRequest {

    @NotBlank(message = "Coupon code is required")
    private String code;

    @NotBlank(message = "Description is required")
    private String description;

    @NotNull(message = "Discount type is required")
    private String discountType; // PERCENTAGE or FIXED_AMOUNT

    @NotNull(message = "Discount value is required")
    @Positive(message = "Discount must be positive")
    private Double discountValue;

    private Double minimumOrderAmount;
    private Double maximumDiscountAmount;

    @NotNull(message = "Valid from date is required")
    private LocalDateTime validFrom;

    @NotNull(message = "Valid until date is required")
    private LocalDateTime validUntil;

    private Integer usageLimit;
    private Integer perUserLimit;
    private Long restaurantId; // null for platform-wide
}