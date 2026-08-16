package com.bhukkad.ratelimit;

public record RateLimitDecision(
        boolean allowed,
        long currentCount,
        int limit,
        int windowSeconds,
        long retryAfterSeconds
) {
    public static RateLimitDecision allowed(long currentCount, int limit, int windowSeconds) {
        return new RateLimitDecision(true, currentCount, limit, windowSeconds, 0);
    }

    public static RateLimitDecision denied(long currentCount, int limit, long retryAfterSeconds) {
        return new RateLimitDecision(false, currentCount, limit, 0, retryAfterSeconds);
    }
}
