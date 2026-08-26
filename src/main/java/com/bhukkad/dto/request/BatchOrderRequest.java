package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for {@code POST /orders/customer/create-batch}.
 *
 * <p>Mirrors the common subset of {@link OrderRequest} fields that apply
 * to all restaurants in the batch. Per-restaurant variation (menu items,
 * specific customizations) is derived from the customer's shared cart.
 * Tip and wallet amounts are split proportionally by the service layer.</p>
 */
@Data
public class BatchOrderRequest {
    @NotNull(message = "Delivery address ID is required")
    private Long deliveryAddressId;

    private String specialInstructions;

    private Boolean contactlessDelivery = false;

    @NotNull(message = "Payment method is required")
    private String paymentMethod;

    /** Total tip for all orders in the batch — split proportionally by subtotal. */
    private Double tipAmount;

    /** Total tip expressed as an explicit sum (convenience for clients). */
    private Double totalTipAmount;

    private String couponCode;

    private Integer loyaltyPointsToRedeem;

    /** Explicit wallet amount to apply (split pay with card/UPI). */
    private Double walletAmountToUse;

    /** Apply available wallet balance up to batch total (split pay). */
    private Boolean useWallet;
}
