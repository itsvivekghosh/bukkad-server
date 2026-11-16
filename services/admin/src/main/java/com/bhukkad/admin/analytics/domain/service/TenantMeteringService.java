package com.bhukkad.admin.analytics.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;

/**
 * Per-tenant usage metering with rolling time windows.
 *
 * <p>Each metered event is a member of a Redis sorted set
 * {@code bhukkad:metering:{tenantId}:{metric}} whose score is the event time
 * in epoch millis. Expired members are pruned on read ({@code ZREMRANGEBYSCORE})
 * so the set cardinality is the rolling-window count — no daily rollup job
 * required.</p>
 *
 * <p>Quota checks ({@link #consumeQuota}) run atomically via a Lua script:
 * prune, count, and reject-or-record in one round trip.</p>
 */
@Slf4j
@Service
public class TenantMeteringService {

    private static final String KEY_PATTERN = "bhukkad:metering:%s:%s";

    private static final String CONSUME_QUOTA_LUA = """
        local key = KEYS[1]
        local nowMs = tonumber(ARGV[1])
        local windowMs = tonumber(ARGV[2])
        local limit = tonumber(ARGV[3])
        local member = ARGV[4]
        redis.call('ZREMRANGEBYSCORE', key, 0, nowMs - windowMs)
        local count = redis.call('ZCARD', key)
        if count >= limit then
            return 0
        end
        redis.call('ZADD', key, nowMs, member)
        return 1
        """;

    private static final DefaultRedisScript<Long> CONSUME_QUOTA;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(CONSUME_QUOTA_LUA);
        script.setResultType(Long.class);
        CONSUME_QUOTA = script;
    }

    private final StringRedisTemplate stringRedisTemplate;

    public TenantMeteringService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static String key(String tenantId, String metric) {
        return KEY_PATTERN.formatted(tenantId, metric);
    }

    /**
     * Records a single metered event for {@code tenantId} under {@code metric}
     * at the current time.
     */
    public void record(String tenantId, String metric) {
        record(tenantId, metric, System.currentTimeMillis());
    }

    /**
     * Records {@code count} metered events for {@code tenantId} under
     * {@code metric} at the current time.
     */
    public void record(String tenantId, String metric, long count) {
        String key = key(tenantId, metric);
        long now = System.currentTimeMillis();
        for (long i = 0; i < count; i++) {
            stringRedisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(), now);
        }
        log.debug("METERING_RECORD | tenant={} metric={} count={}", tenantId, metric, count);
    }

    /**
     * Returns the number of events recorded for {@code tenantId} under
     * {@code metric} within the last {@code windowSeconds} seconds.
     */
    public long countInWindow(String tenantId, String metric, long windowSeconds) {
        String key = key(tenantId, metric);
        long now = System.currentTimeMillis();
        stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0, now - windowSeconds * 1000);
        Long cardinality = stringRedisTemplate.opsForZSet().zCard(key);
        return cardinality == null ? 0 : cardinality;
    }

    /**
     * Atomically checks the rolling-window quota and records the event if
     * allowed. Returns {@code true} when the event is within quota and was
     * recorded, {@code false} when the quota is exhausted.
     */
    public boolean consumeQuota(String tenantId, String metric, long limit, long windowSeconds) {
        Long allowed = stringRedisTemplate.execute(
                CONSUME_QUOTA,
                Collections.singletonList(key(tenantId, metric)),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(windowSeconds * 1000L),
                String.valueOf(limit),
                UUID.randomUUID().toString());
        boolean result = Boolean.TRUE.equals(allowed);
        log.debug("METERING_QUOTA | tenant={} metric={} limit={} windowSeconds={} allowed={}",
                tenantId, metric, limit, windowSeconds, result);
        return result;
    }

    /**
     * Clears all metering data for {@code tenantId} under {@code metric}
     * (tests / tenant offboarding).
     */
    public void reset(String tenantId, String metric) {
        stringRedisTemplate.delete(key(tenantId, metric));
    }
}
