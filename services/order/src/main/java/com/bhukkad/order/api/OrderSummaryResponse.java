package com.bhukkad.order.api;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class OrderSummaryResponse {

    private Long id;
    private String orderNumber;
    private Long customerId;
    private String customerName;
    private Long restaurantId;
    private String restaurantName;
    private String status;
    private BigDecimal totalAmount;
    private String specialInstructions;
    private LocalDateTime createdAt;
    private LocalDateTime estimatedDeliveryAt;

    public OrderSummaryResponse(
            Long id,
            String orderNumber,
            Long customerId,
            String customerName,
            Long restaurantId,
            String restaurantName,
            String status,
            BigDecimal totalAmount,
            String specialInstructions,
            LocalDateTime createdAt,
            LocalDateTime estimatedDeliveryAt) {
        this.id = id;
        this.orderNumber = orderNumber;
        this.customerId = customerId;
        this.customerName = customerName;
        this.restaurantId = restaurantId;
        this.restaurantName = restaurantName;
        this.status = status;
        this.totalAmount = totalAmount;
        this.specialInstructions = specialInstructions;
        this.createdAt = createdAt;
        this.estimatedDeliveryAt = estimatedDeliveryAt;
    }
}
