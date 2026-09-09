package com.bhukkad.common.kafka;

/**
 * Signals an event that can never be processed by this consumer (malformed
 * envelope, missing mandatory field, phantom subject). Throwing it from a
 * {@code @KafkaListener} body lets the container's
 * {@link org.springframework.kafka.listener.DefaultErrorHandler} retry and
 * ultimately route the record to {@code <topic>.dlt} — the alternative
 * (silent fallback values like {@code customer-0}) fabricates business
 * artifacts from garbage input (audit V-22).
 */
public class PoisonEventException extends RuntimeException {

    public PoisonEventException(String message) {
        super(message);
    }

    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
