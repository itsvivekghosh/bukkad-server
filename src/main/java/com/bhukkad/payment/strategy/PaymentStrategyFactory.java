package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Payment;
import com.bhukkad.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentStrategyFactory {

    private final GatewayPaymentStrategy gatewayPaymentStrategy;
    private final CODPaymentStrategy codPaymentStrategy;
    private final WalletPaymentStrategy walletPaymentStrategy;

    public PaymentStrategy getStrategy(Payment.PaymentMethod method) {
        return switch (method) {
            case CASH_ON_DELIVERY -> codPaymentStrategy;
            case WALLET -> walletPaymentStrategy;
            case CREDIT_CARD, DEBIT_CARD, UPI, NET_BANKING -> gatewayPaymentStrategy;
            default -> throw new BusinessException("Unsupported payment method: " + method);
        };
    }
}
