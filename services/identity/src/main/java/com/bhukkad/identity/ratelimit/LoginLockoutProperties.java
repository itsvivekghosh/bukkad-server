package com.bhukkad.identity.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Brute-force lockout knobs (feature #5): Redis-backed failed-login counters
 * per (email, IP) with an exponential lockout window.
 *
 * <p>Escalation: the lock duration doubles per consecutive lockout episode
 * (2^(strikes-1) × baseLockSeconds), capped at maxLockSeconds. A successful
 * login clears the failure count, the lock and the escalation counter.</p>
 */
@ConfigurationProperties(prefix = "app.auth.lockout")
public record LoginLockoutProperties(int threshold,
                                     int failureWindowSeconds,
                                     int baseLockSeconds,
                                     int maxLockSeconds,
                                     int strikesTtlSeconds) {

    public LoginLockoutProperties {
        if (threshold <= 0) {
            threshold = 5;
        }
        if (failureWindowSeconds <= 0) {
            failureWindowSeconds = 900;
        }
        if (baseLockSeconds <= 0) {
            baseLockSeconds = 300;
        }
        if (maxLockSeconds <= 0) {
            maxLockSeconds = 3600;
        }
        if (strikesTtlSeconds <= 0) {
            strikesTtlSeconds = 86_400;
        }
    }

    public static LoginLockoutProperties defaults() {
        return new LoginLockoutProperties(5, 900, 300, 3600, 86_400);
    }
}
