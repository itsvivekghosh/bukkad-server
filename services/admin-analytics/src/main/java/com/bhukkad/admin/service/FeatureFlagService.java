package com.bhukkad.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime feature-flag store. Initial values come from
 * {@link FeatureFlagProperties}; operators can toggle flags for gradual
 * rollouts and instant rollback without redeploys.
 *
 * <p>Runtime overrides are stored in Redis (a single hash keyed by flag name)
 * so a kill-switch flip or rollout toggle issued against one replica takes
 * effect on <b>every</b> replica. A small per-instance cache keeps the hot
 * {@code isEnabled} path free of a Redis round-trip; it is written through on
 * every local toggle and evicted cluster-wide via a Redis pub/sub message
 * ({@link #onMessage}), so a change made on any replica is visible on all of
 * them within the delivery latency of the channel.</p>
 *
 * <p>Supports two activation modes per flag:</p>
 * <ul>
 *   <li><b>Boolean switch</b> — {@code isEnabled(key)} returns the configured
 *       or overridden value for every caller.</li>
 *   <li><b>Percentage rollout</b> — when a flag has a {@code rollout} percentage
 *       (0–100), {@code isEnabled(key, userId)} enables it deterministically for
 *       a fraction of users via a stable hash of the user id.</li>
 * </ul>
 *
 * <p>Every runtime toggle is audit-logged (who/what/when) so a kill-switch flip
 * is traceable in production.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService implements MessageListener {

    /** Redis hash holding all runtime overrides: field = flag key, value = "true"/"false". */
    static final String OVERRIDES_HASH = "bhukkad:feature-flag:overrides";

    /** Redis pub/sub channel used to invalidate the local override cache on other replicas. */
    static final String CHANGED_CHANNEL = "bhukkad:feature-flag:changed";

    private final FeatureFlagProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Per-instance write-through cache of override values. Keeping the hot path
     * in-process is important because {@code isEnabled} is called on every
     * request path; Redis is the source of truth for cluster consistency and
     * pub/sub messages evict entries when another replica toggles a flag.
     */
    private final Map<String, Boolean> overrideCache = new ConcurrentHashMap<>();

    /** Global flag check (no user context). */
    public boolean isEnabled(String key) {
        return readOverride(key).orElseGet(() -> properties.isEnabled(key));
    }

    /**
     * User-scoped flag check. When the flag has a configured rollout percentage,
     * a stable hash of the user id decides activation; otherwise falls back to
     * the global boolean switch.
     */
    public boolean isEnabled(String key, Long userId) {
        Integer rolloutPercent = properties.getRolloutPercent(key);
        if (rolloutPercent == null) {
            return isEnabled(key);
        }
        boolean global = isEnabled(key);
        if (!global || userId == null || rolloutPercent >= 100) {
            return global;
        }
        if (rolloutPercent <= 0) {
            return false;
        }
        return (stableHash(key, userId) % 100) < rolloutPercent;
    }

    /**
     * Sets a runtime override in Redis (visible to all replicas) and notifies
     * other replicas to evict their local cache via pub/sub. Pass {@code null}
     * to revert to config value. Logs the change for the kill-switch audit
     * trail.
     */
    public void setFlag(String key, Boolean enabled) {
        Boolean previous = readOverride(key).orElse(properties.isEnabled(key));
        try {
            if (enabled == null) {
                stringRedisTemplate.opsForHash().delete(OVERRIDES_HASH, key);
            } else {
                stringRedisTemplate.opsForHash().put(OVERRIDES_HASH, key, String.valueOf(enabled));
            }
            stringRedisTemplate.convertAndSend(CHANGED_CHANNEL, key);
            if (enabled == null) {
                overrideCache.remove(key);
            } else {
                overrideCache.put(key, enabled);
            }
        } catch (Exception ex) {
            log.warn("FEATURE_FLAG_REDIS_FAILED | key={} | to={} | error={}", key, enabled, ex.getMessage());
            if (enabled == null) {
                overrideCache.remove(key);
            } else {
                overrideCache.put(key, enabled);
            }
        }
        log.info("FEATURE_FLAG_CHANGED | key={} | from={} | to={}", key, previous, enabled);
    }

    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        properties.getFlags().forEach(result::put);
        // Read overrides from Redis (source of truth) and overlay on config.
        try {
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(OVERRIDES_HASH);
            entries.forEach((k, v) -> result.put(String.valueOf(k), Boolean.parseBoolean(String.valueOf(v))));
            entries.keySet().forEach(k -> {
                String key = String.valueOf(k);
                overrideCache.put(key, result.get(key));
            });
        } catch (Exception ex) {
            log.warn("FEATURE_FLAG_SNAPSHOT_REDIS_FAILED | error={}", ex.getMessage());
        }
        return result;
    }

    /**
     * Pub/sub listener: evicts the local override cache entry for a flag that
     * was toggled on another replica, so the next read hits Redis and picks up
     * the new value.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String key = new String(message.getBody());
            Boolean evicted = overrideCache.remove(key);
            if (evicted != null) {
                log.debug("FEATURE_FLAG_CACHE_EVICTED | key={}", key);
            }
        } catch (Exception ex) {
            log.warn("FEATURE_FLAG_INVALIDATION_FAILED | error={}", ex.getMessage());
        }
    }

    /**
     * Returns the override value for a flag, checking the local write-through
     * cache first and falling back to Redis on a miss.
     */
    private Optional<Boolean> readOverride(String key) {
        Boolean cached = overrideCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            Object value = stringRedisTemplate.opsForHash().get(OVERRIDES_HASH, key);
            if (value != null) {
                Boolean parsed = Boolean.parseBoolean(String.valueOf(value));
                overrideCache.put(key, parsed);
                return Optional.of(parsed);
            }
        } catch (Exception ex) {
            log.warn("FEATURE_FLAG_READ_REDIS_FAILED | key={} | error={}", key, ex.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Deterministic, stable hash of (flagKey, userId) so the same user always
     * sees the same flag state for a given percentage (no flapping between
     * requests).
     */
    private int stableHash(String key, Long userId) {
        long h = 1125899906842597L; // large prime seed
        String input = key + ":" + userId;
        for (char c : input.toCharArray()) {
            h = 31 * h + c;
        }
        return (int) Math.abs(h % 100);
    }
}
