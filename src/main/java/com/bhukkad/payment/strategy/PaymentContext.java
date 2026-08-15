package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;

public record PaymentContext(
    Order order,
    Payment payment,
    String idempotencyKey,
    double gatewayAmount
) {}
