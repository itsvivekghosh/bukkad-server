package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    private Long id;
    private String orderNumber;
    private Long customerId;
    private String customerName;
    private Long restaurantId;
    private String restaurantName;
    private List<OrderItemResponse> items;
    private AddressResponse deliveryAddress;
    private String status;
    private Double subtotal;
    private Double deliveryFee;
    private Double taxAmount;
    private Double discountAmount;
    private Double totalAmount;
    private Double tipAmount;
    private String paymentMethod;
    private String paymentStatus;
    private String specialInstructions;
    private Boolean contactlessDelivery;
    private Integer estimatedDeliveryTime;
    private LocalDateTime estimatedDeliveryAt;
    private LocalDateTime scheduledAt;
    private Integer liveEtaMinutes;
    private LocalDateTime liveEtaAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime createdAt;
    private DeliveryAgentResponse deliveryAgent;
}