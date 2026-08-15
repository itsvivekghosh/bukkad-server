package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Payment;

public interface PaymentStrategy {
    Payment process(PaymentContext context);
}
