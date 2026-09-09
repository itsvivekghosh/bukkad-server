package com.bhukkad.common.ratelimit;

/**
 * Rate-limit primitive used by {@link RateLimitAspect}. Services may provide a
 * Redis-backed implementation; the common module ships an in-memory fixed-window
 * implementation for development and single-instance deployments.
 */
public interface RateLimitService {

    RateLimitDecision check(String bucket, String identifier, long limit, int windowSeconds);
}
