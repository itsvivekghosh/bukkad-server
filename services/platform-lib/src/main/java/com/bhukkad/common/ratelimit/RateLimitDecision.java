package com.bhukkad.common.ratelimit;

/**
 * Result of a rate-limit decision.
 *
 * @param allowed         whether the request is allowed
 * @param count           observed count within the window
 * @param limit           configured limit for the bucket+tier
 * @param retryAfterSeconds seconds to wait before retrying (0 when allowed)
 */
public record RateLimitDecision(boolean allowed, long count, long limit, long retryAfterSeconds) {

    public static RateLimitDecision allowed(long count, long limit, long windowSeconds) {
        return new RateLimitDecision(true, count, limit, 0);
    }

    public static RateLimitDecision denied(long count, long limit, long retryAfterSeconds) {
        return new RateLimitDecision(false, count, limit, retryAfterSeconds);
    }
}
