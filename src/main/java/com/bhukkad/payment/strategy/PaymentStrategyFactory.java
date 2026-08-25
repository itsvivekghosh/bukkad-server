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
    private final BNPLStrategy bnplStrategy;

    public PaymentStrategy getStrategy(Payment.PaymentMethod method) {
        return switch (method) {
            case CASH_ON_DELIVERY -> codPaymentStrategy;
            case WALLET -> walletPaymentStrategy;
            case BNPL -> bnplStrategy;
            case CREDIT_CARD, DEBIT_CARD, UPI, NET_BANKING -> gatewayPaymentStrategy;
            default -> throw new BusinessException("Unsupported payment method: " + method);
        };
    }
}
