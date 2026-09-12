package com.bhukkad.common.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exception hierarchy contract: codes, causes and fraud metadata surfaced by
 * the GlobalExceptionHandler mapping.
 */
class ErrorTypesCoverageTest {

    @Test
    void businessException_carriesCodeAcrossConstructors() {
        assertThat(new BusinessException("plain").getCode()).isEqualTo(BusinessException.DEFAULT_CODE);
        assertThat(new BusinessException("plain").getMessage()).isEqualTo("plain");
        RuntimeException cause = new IllegalStateException("root");
        assertThat(new BusinessException("with-cause", cause).getCause()).isSameAs(cause);
        assertThat(new BusinessException("CART_EMPTY", "code+message").getCode())
                .isEqualTo("CART_EMPTY");
        assertThat(new BusinessException("CODE", "msg", cause).getCode()).isEqualTo("CODE");
        assertThat(new BusinessException("CODE", "msg", cause).getCause()).isSameAs(cause);
    }

    @Test
    void unauthorizedException_messageAndCause() {
        assertThat(new UnauthorizedException("nope").getMessage()).isEqualTo("nope");
        IllegalStateException root = new IllegalStateException("token expired");
        UnauthorizedException withCause = new UnauthorizedException("nope", root);
        assertThat(withCause.getCause()).isSameAs(root);
    }

    @Test
    void fraudBlocked_carriesEventTypeAndRetryWindow() {
        FraudBlockedException fraud = new FraudBlockedException("temporarily blocked",
                "VELOCITY_BURST", 42L);
        assertThat(fraud.getMessage()).isEqualTo("temporarily blocked");
        assertThat(fraud.getEventType()).isEqualTo("VELOCITY_BURST");
        assertThat(fraud.getRetryAfterSeconds()).isEqualTo(42L);
    }

    @Test
    void paymentGatewayExceptions_messageAndCause() {
        assertThat(new PaymentGatewayException("gateway 5xx").getMessage()).isEqualTo("gateway 5xx");
        RuntimeException cause = new RuntimeException("socket");
        assertThat(new PaymentGatewayException("io", cause).getCause()).isSameAs(cause);
    }

    @Test
    void sseCapacityExceededException_isRuntimeWithMessage() {
        SseCapacityExceededException ex = new SseCapacityExceededException("max streams");
        assertThat(ex).isInstanceOf(RuntimeException.class).hasMessage("max streams");
    }
}
