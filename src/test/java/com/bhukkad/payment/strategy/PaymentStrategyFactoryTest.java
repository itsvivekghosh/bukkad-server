package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PaymentStrategyFactoryTest {

    @Mock
    private GatewayPaymentStrategy gatewayPaymentStrategy;
    @Mock
    private CODPaymentStrategy codPaymentStrategy;
    @Mock
    private WalletPaymentStrategy walletPaymentStrategy;

    private PaymentStrategyFactory factory;

    @BeforeEach
    void setUp() {
        factory = new PaymentStrategyFactory(gatewayPaymentStrategy, codPaymentStrategy, walletPaymentStrategy);
    }

    @Test
    void testGetStrategy_CashOnDelivery_ReturnsCODStrategy() {
        PaymentStrategy strategy = factory.getStrategy(Payment.PaymentMethod.CASH_ON_DELIVERY);
        assertSame(codPaymentStrategy, strategy);
    }

    @Test
    void testGetStrategy_CreditCard_ReturnsGatewayStrategy() {
        PaymentStrategy strategy = factory.getStrategy(Payment.PaymentMethod.CREDIT_CARD);
        assertSame(gatewayPaymentStrategy, strategy);
    }

    @Test
    void testGetStrategy_DebitCard_ReturnsGatewayStrategy() {
        PaymentStrategy strategy = factory.getStrategy(Payment.PaymentMethod.DEBIT_CARD);
        assertSame(gatewayPaymentStrategy, strategy);
    }

    @Test
    void testGetStrategy_UPI_ReturnsGatewayStrategy() {
        PaymentStrategy strategy = factory.getStrategy(Payment.PaymentMethod.UPI);
        assertSame(gatewayPaymentStrategy, strategy);
    }

    @Test
    void testGetStrategy_NetBanking_ReturnsGatewayStrategy() {
        PaymentStrategy strategy = factory.getStrategy(Payment.PaymentMethod.NET_BANKING);
        assertSame(gatewayPaymentStrategy, strategy);
    }

    @Test
    void testGetStrategy_Wallet_ReturnsWalletStrategy() {
        PaymentStrategy strategy = factory.getStrategy(Payment.PaymentMethod.WALLET);
        assertSame(walletPaymentStrategy, strategy);
    }

    @Test
    void testGetStrategy_AllPaymentMethods_Covered() {
        for (Payment.PaymentMethod method : Payment.PaymentMethod.values()) {
            PaymentStrategy strategy = factory.getStrategy(method);
            assertNotNull(strategy, "No strategy should be null for: " + method);
        }
    }
}
