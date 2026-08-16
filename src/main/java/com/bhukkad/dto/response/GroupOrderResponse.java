package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupOrderResponse {
    private Long id;
    private String orderNumber;
    private Long restaurantId;
    private String restaurantName;
    private List<Long> participatingCustomers;
    private Long primaryCustomerId;
    private String status;
    private Double subtotal;
    private Double deliveryFee;
    private Double taxAmount;
    private Double discountAmount;
    private Double totalAmount;
    private Double tipAmount;
    private String specialInstructions;
    private Boolean contactlessDelivery;
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime cancelledAt;
    private String cancellationReason;
    private String paymentMethod;
    private Integer loyaltyPointsRedeemed;
    private Double walletAmountUsed;
}