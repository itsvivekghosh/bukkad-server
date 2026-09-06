package com.bhukkad.payment.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private Long id;
    private Long orderId;
    private Long customerId;
    private String paymentMethod;
    private String status;
    private BigDecimal amount;
    private BigDecimal walletAmount;
    private BigDecimal gatewayAmount;
    private String gatewayOrderId;
    private String gatewayPaymentId;
    private String transactionId;
    private String purpose;
    private String createdAt;
    private String completedAt;
}
