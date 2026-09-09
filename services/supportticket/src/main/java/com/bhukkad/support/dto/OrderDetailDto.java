package com.bhukkad.support.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for order details needed for dispute auto-resolution and refund calculation.
 */
public class OrderDetailDto {
    private Long orderId;
    private Long customerId;
    private Long restaurantId;
    private String status;
    private BigDecimal totalAmount;
    private LocalDateTime deliveredAt;
    private LocalDateTime estimatedDeliveryAt;

    public OrderDetailDto() {}

    public OrderDetailDto(Long orderId, Long customerId, Long restaurantId, String status,
                          BigDecimal totalAmount, LocalDateTime deliveredAt, LocalDateTime estimatedDeliveryAt) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.restaurantId = restaurantId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.deliveredAt = deliveredAt;
        this.estimatedDeliveryAt = estimatedDeliveryAt;
    }

    // Getters and Setters
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public Long getRestaurantId() { return restaurantId; }
    public void setRestaurantId(Long restaurantId) { this.restaurantId = restaurantId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(LocalDateTime deliveredAt) { this.deliveredAt = deliveredAt; }
    public LocalDateTime getEstimatedDeliveryAt() { return estimatedDeliveryAt; }
    public void setEstimatedDeliveryAt(LocalDateTime estimatedDeliveryAt) { this.estimatedDeliveryAt = estimatedDeliveryAt; }
}
