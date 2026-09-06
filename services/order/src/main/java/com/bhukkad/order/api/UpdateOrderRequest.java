package com.bhukkad.order.api;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UpdateOrderRequest {
    @NotNull(message = "Order ID is required")
    private Long orderId;

    private String status;

    private String specialInstructions;

    private Boolean contactlessDelivery;

    private LocalDateTime scheduledAt;

    private Integer estimatedDeliveryTime;

    private String cancellationReason;

    private String cancelledBy;
}
