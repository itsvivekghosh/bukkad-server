package com.bhukkad.common.ratelimit;

import com.bhukkad.common.redis.RedisConfig;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed distributed rate limiter.
 *
 * <p>Uses a fixed-window counter with atomic INCR + EXPIRE via
 * {@link StringRedisTemplate}. This is safe across multiple service instances
 * and replaces the in-memory implementation in production.</p>
 */
@Service
public class RedisRateLimitService implements RateLimitService {

    private static final String PREFIX = "bhukkad:ratelimit:";

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RateLimitDecision check(String bucket, String identifier, long limit, int windowSeconds) {
        String key = PREFIX + bucket + ":" + identifier;
        Long count = redisTemplate.opsForValue().increment(key);

        if (count != null && count == 1) {
            redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }

        if (count != null && count > limit) {
            long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            long retryAfter = (ttl > 0 ? ttl : windowSeconds);
            return RateLimitDecision.denied(count, limit, Math.max(1, retryAfter));
        }

        return RateLimitDecision.allowed(count != null ? count : 0, limit, windowSeconds);
    }
}
