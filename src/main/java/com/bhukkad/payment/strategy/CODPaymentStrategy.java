package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Payment;
import org.springframework.stereotype.Component;

@Component
public class CODPaymentStrategy implements PaymentStrategy {

    @Override
    public Payment process(PaymentContext context) {
        Payment payment = context.payment();
        payment.setStatus(Payment.PaymentStatus.PENDING);
        return payment;
    }
}
