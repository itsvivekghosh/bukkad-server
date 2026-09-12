package com.bhukkad.order.api.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.bhukkad.order.domain.entity.Order;

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
