package com.bhukkad.engagement.fraud.domain.entity;

import java.time.LocalDateTime;

/**
 * Represents a single fraud detection flag/alert.
 */
public record FraudFlag(
        String code,
        String description,
        LocalDateTime detectedAt,
        double severity
) {
    public FraudFlag(String code, String description) {
        this(code, description, LocalDateTime.now(), 1.0);
    }

    public static FraudFlag of(String code, String description) {
        return new FraudFlag(code, description);
    }

    public static FraudFlag of(String code, String description, double severity) {
        return new FraudFlag(code, description, LocalDateTime.now(), severity);
    }
}