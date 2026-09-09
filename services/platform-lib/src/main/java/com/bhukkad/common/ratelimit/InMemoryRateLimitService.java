package com.bhukkad.common.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window rate limiter for development and single-instance
 * deployments. Redis replaces this in multi-instance production (the monolith's
 * Lua INCR+EXPIRE pattern). Per-bucket+key state is pruned lazily when a
 * window expires.
 */
public class InMemoryRateLimitService implements RateLimitService {

    private record BucketState(long windowStartedAtMillis, long count) {
    }

    private final Map<String, BucketState> state = new ConcurrentHashMap<>();

    @Override
    public RateLimitDecision check(String bucket, String identifier, long limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;
        String key = bucket + ":" + identifier;

        BucketState current = state.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartedAtMillis() >= windowMillis) {
                return new BucketState(now, 1);
            }
            return new BucketState(existing.windowStartedAtMillis(), existing.count() + 1);
        });

        if (current.count() > limit) {
            long retryAfter = (current.windowStartedAtMillis() + windowMillis - now + 999) / 1000;
            return RateLimitDecision.denied(current.count(), limit, Math.max(1, retryAfter));
        }
        return RateLimitDecision.allowed(current.count(), limit, windowSeconds);
    }
}
