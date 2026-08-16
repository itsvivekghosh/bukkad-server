package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private Long id;
    private Long orderId;
    private String paymentMethod;
    private String status;
    private Double amount;
    private String gatewayOrderId;
    private String gatewayPaymentId;
    private String transactionId;

    private String purpose;
}
