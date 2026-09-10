package com.bhukkad.growth.api;

/**
 * Raised when a loyalty credit would push a customer over the configured
 * per-customer daily credit cap (abuse ceiling, audit feature #4). Mapped to
 * HTTP 422 by {@link GrowthExceptionHandler}.
 */
public class LoyaltyDailyCapExceededException extends RuntimeException {

    public LoyaltyDailyCapExceededException(String message) {
        super(message);
    }
}
