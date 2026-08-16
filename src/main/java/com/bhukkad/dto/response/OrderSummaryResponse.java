package com.bhukkad.dto.response;

import com.bhukkad.entity.Order;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Lightweight order projection for list and kitchen-queue APIs.
 * Avoids loading address, owner, and order-item graphs.
 */
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
    private Double totalAmount;
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
            Order.OrderStatus status,
            Double totalAmount,
            String specialInstructions,
            LocalDateTime createdAt,
            LocalDateTime estimatedDeliveryAt) {
        this.id = id;
        this.orderNumber = orderNumber;
        this.customerId = customerId;
        this.customerName = customerName;
        this.restaurantId = restaurantId;
        this.restaurantName = restaurantName;
        this.status = status != null ? status.name() : null;
        this.totalAmount = totalAmount;
        this.specialInstructions = specialInstructions;
        this.createdAt = createdAt;
        this.estimatedDeliveryAt = estimatedDeliveryAt;
    }
}
