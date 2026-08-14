package com.bhukkad.ratelimit;

import com.bhukkad.cache.CacheConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRE = new DefaultRedisScript<>(
            """
                    local current = redis.call('INCR', KEYS[1])
                    if current == 1 then
                      redis.call('EXPIRE', KEYS[1], ARGV[1])
                    end
                    return current
                    """,
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final RateLimitProperties properties;

    public RateLimitDecision check(String bucket, String identifier) {
        RateLimitProperties.Bucket config = properties.getBucket(bucket);
        int limit = config.getLimit();
        int windowSeconds = config.getWindowSeconds();

        if (!properties.isEnabled()) {
            return RateLimitDecision.allowed(0, limit, windowSeconds);
        }

        String key = CacheConstants.KEY_PREFIX + "rate-limit:" + bucket + ":" + identifier;
        try {
            Long count = stringRedisTemplate.execute(
                    INCREMENT_WITH_EXPIRE,
                    List.of(key),
                    String.valueOf(windowSeconds));

            long currentCount = count != null ? count : 1L;
            if (currentCount > limit) {
                Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
                long retryAfter = ttl != null && ttl > 0 ? ttl : windowSeconds;
                log.warn("RATE_LIMIT_EXCEEDED | bucket={} | identifier={} | count={} | limit={}",
                        bucket, identifier, currentCount, limit);
                return RateLimitDecision.denied(currentCount, limit, retryAfter);
            }

            return RateLimitDecision.allowed(currentCount, limit, windowSeconds);
        } catch (Exception ex) {
            log.warn("RATE_LIMIT_CHECK_FAILED | bucket={} | identifier={} | error={}",
                    bucket, identifier, ex.getMessage());
            return RateLimitDecision.allowed(0, limit, windowSeconds);
        }
    }
}
