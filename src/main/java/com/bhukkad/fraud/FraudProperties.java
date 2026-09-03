package com.bhukkad.fraud;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Tunable abuse-velocity thresholds, bound from {@code app.fraud} in {@code application.yml}.
 *
 * <p>Thresholds are per event type because a single global number cannot serve both registration
 * (a handful of accounts per network per hour is already suspicious) and login (an office or mobile
 * carrier NAT legitimately produces dozens of logins per hour from one address).</p>
 *
 * <p>Defaults are deliberately loose. A false block on checkout or login is a lost order and a
 * support ticket, so the initial values are set to catch scripted abuse rather than heavy human
 * use; they are meant to be tightened once real traffic distributions are observed. Enforcement can
 * be switched off entirely with {@code app.fraud.blocking-enabled: false}, which keeps logging (and
 * therefore the data needed to pick thresholds) while removing customer impact.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.fraud")
public class FraudProperties {

    /** Master switch for recording fraud events at all. */
    private boolean enabled = true;

    /**
     * When {@code false}, events are still recorded and suspicious activity is still logged and
     * alerted, but no request is rejected. This is the safe way to roll the feature out: observe
     * first, enforce once the thresholds are known to be sane.
     */
    private boolean blockingEnabled = true;

    /** Sliding window, in minutes, over which events are counted. */
    private int windowMinutes = 60;

    /** Seconds advertised in {@code Retry-After} when a request is blocked. */
    private long retryAfterSeconds = 300;

    /** Threshold used when an event type has no explicit entry in {@link #thresholds}. */
    private int defaultThreshold = 20;

    /** Per-event-type thresholds, keyed by the labels in {@link FraudEventTypes}. */
    private Map<String, Threshold> thresholds = defaultThresholds();

    /**
     * Resolves the threshold for an event type, falling back to {@link #defaultThreshold} so a
     * newly introduced event type can never produce a null-threshold failure at runtime.
     *
     * @param eventType label from {@link FraudEventTypes}
     * @return a non-null threshold
     */
    public Threshold thresholdFor(String eventType) {
        Threshold configured = thresholds.get(eventType);
        if (configured != null) {
            return configured;
        }
        Threshold fallback = defaultThresholds().get(eventType);
        return fallback != null ? fallback : new Threshold(defaultThreshold, defaultThreshold);
    }

    /**
     * Built-in thresholds, used when {@code app.fraud.thresholds} is absent from configuration.
     *
     * <p>Login is the loosest because shared egress IPs are common; registration is the tightest
     * because bulk account creation is the abuse this feature exists to stop.</p>
     */
    private static Map<String, Threshold> defaultThresholds() {
        Map<String, Threshold> defaults = new HashMap<>();
        defaults.put(FraudEventTypes.AUTH_REGISTER, new Threshold(10, 5));
        defaults.put(FraudEventTypes.AUTH_LOGIN, new Threshold(40, 25));
        defaults.put(FraudEventTypes.ORDER_CREATE, new Threshold(25, 15));
        return defaults;
    }

    /**
     * Per-dimension limits for one event type. Both dimensions are counted independently and
     * either one tripping is enough to block.
     */
    @Data
    public static class Threshold {

        /**
         * Maximum events allowed from one IP address in the window. Catches distributed abuse that
         * rotates emails and device identifiers but not network origin.
         */
        private int perIp;

        /**
         * Maximum events allowed from one device fingerprint in the window. Catches abuse that
         * rotates IPs (proxy pools, mobile data cycling) from a single device. Only applies when
         * the client sends {@code X-Device-Fingerprint}.
         */
        private int perDevice;

        public Threshold() {
        }

        public Threshold(int perIp, int perDevice) {
            this.perIp = perIp;
            this.perDevice = perDevice;
        }
    }
}
