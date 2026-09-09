package com.bhukkad.payment.gateway;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

    @Test
    void authorize_smallAmount_succeeds() {
        var result = gateway.authorize(1L, 2L, new BigDecimal("500.00"), "INR");
        assertThat(result.success()).isTrue();
        assertThat(result.providerRef()).startsWith("SIM-PROV-");
    }

    @Test
    void authorize_overLimit_declined() {
        var result = gateway.authorize(1L, 2L, new BigDecimal("50000.00"), "INR");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("limit");
    }

    @Test
    void authorize_limitExact_succeeds() {
        var result = gateway.authorize(1L, 2L, new BigDecimal("10000.00"), "INR");
        assertThat(result.success()).isTrue();
    }

    @Test
    void refund_positive_succeeds() {
        var result = gateway.refund(1L, new BigDecimal("100.00"));
        assertThat(result.success()).isTrue();
        assertThat(result.providerRef()).startsWith("SIM-REF-");
    }

    @Test
    void refund_nonPositive_fails() {
        var result = gateway.refund(1L, BigDecimal.ZERO);
        assertThat(result.success()).isFalse();
    }
}
