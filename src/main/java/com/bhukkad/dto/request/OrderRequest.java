package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OrderRequest {
    @NotNull(message = "Restaurant ID is required")
    private Long restaurantId;

    @NotNull(message = "Delivery address ID is required")
    private Long deliveryAddressId;

    private String specialInstructions;

    private Boolean contactlessDelivery = false;

    private String couponCode;

    @NotNull(message = "Payment method is required")
    private String paymentMethod;

    private Integer loyaltyPointsToRedeem;

    /** Explicit wallet amount to apply (split pay with card/UPI). */
    private Double walletAmountToUse;

    /** Apply available wallet balance up to order total (split pay). */
    private Boolean useWallet;
}