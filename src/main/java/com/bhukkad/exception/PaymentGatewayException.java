package com.bhukkad.exception;

/**
 * Thrown when an external payment gateway call fails due to a transport or
 * protocol error (HTTP timeout, connection failure, malformed response).
 *
 * <p>Deliberately <strong>not</strong> a {@link BusinessException} so that
 * Resilience4j {@code @Retry} and {@code @CircuitBreaker} annotations treat it
 * as a retryable failure. {@code BusinessException} is in the configured
 * {@code ignore-exceptions} list, which would make resilience annotations dead
 * code for gateway transport errors.
 */
public class PaymentGatewayException extends RuntimeException {
    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
