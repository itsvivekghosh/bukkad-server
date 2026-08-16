package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class WalletPaymentStrategy implements PaymentStrategy {

    @Override
    public Payment process(PaymentContext context) {
        Payment payment = context.payment();
        payment.setTransactionId("WALLET-" + payment.getOrder().getOrderNumber());
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setCompletedAt(LocalDateTime.now());
        log.debug("Wallet payment completed | paymentId={} | orderId={}",
                payment.getId(), payment.getOrder().getId());
        return payment;
    }
}
