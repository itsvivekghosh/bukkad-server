package com.bhukkad.order.api;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BatchOrderRequest {
    @NotNull(message = "Delivery address ID is required")
    private Long deliveryAddressId;

    private String specialInstructions;

    private Boolean contactlessDelivery = false;

    @NotNull(message = "Payment method is required")
    private String paymentMethod;

    private BigDecimal tipAmount;

    private String couponCode;

    private Integer loyaltyPointsToRedeem;

    private BigDecimal walletAmountToUse;

    private Boolean useWallet;
}
