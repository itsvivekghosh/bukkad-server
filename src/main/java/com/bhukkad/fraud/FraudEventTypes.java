package com.bhukkad.fraud;

/**
 * Canonical {@code fraud_events.event_type} labels.
 *
 * <p>Event type is part of every velocity query and of the composite indexes added in V17, so the
 * labels must stay stable: renaming one silently resets its counters and orphans historical rows.
 * Values are kept at or under 50 characters to fit {@code fraud_events.event_type}.</p>
 */
public final class FraudEventTypes {

    /** Account creation attempt. */
    public static final String AUTH_REGISTER = "AUTH_REGISTER";

    /** Credential submission at login (successful or not — the check runs before authentication). */
    public static final String AUTH_LOGIN = "AUTH_LOGIN";

    /** Checkout attempt, evaluated before the order is persisted. */
    public static final String ORDER_CREATE = "ORDER_CREATE";

    /** Refund or dispute request; recorded for reporting, not currently blocked. */
    public static final String REFUND_REQUEST = "REFUND_REQUEST";

    /** Promo/coupon redemption attempt; recorded for reporting, not currently blocked. */
    public static final String PROMO_REDEMPTION = "PROMO_REDEMPTION";

    private FraudEventTypes() {
        // Constants holder.
    }
}
