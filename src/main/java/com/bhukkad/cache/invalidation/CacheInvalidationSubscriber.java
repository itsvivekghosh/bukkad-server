package com.bhukkad.cache.invalidation;

import com.bhukkad.cache.LocalCacheService;
import com.bhukkad.cache.event.CacheInvalidatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Listens for cache-invalidation messages published by other instances and
 * evicts the matching entries from the local Redis (L2) + Caffeine (L1) caches.
 *
 * <p>The companion publisher is {@link DistributedCacheInvalidator}. Invalidation
 * is best-effort: any failure is logged and swallowed so a transient Redis issue
 * can never break the request path that triggered the write.
 *
 * <p>Two correctness details are load-bearing:
 * <ul>
 *   <li><b>L1 eviction:</b> entries are served from Caffeine before Redis on the
 *   read path, so a remote invalidation that only deletes L2 would keep serving
 *   stale data for up to the L1 TTL. Single-key events evict the exact L1 key;
 *   pattern events drop the whole (bounded) L1 cache because Caffeine has no
 *   prefix eviction.</li>
 *   <li><b>No blocking KEYS:</b> pattern deletes iterate with bounded SCAN
 *   batches instead of {@code KEYS}, which blocks the Redis instance for the
 *   duration of the scan.</li>
 * </ul>
 */
@Component
public class CacheInvalidationSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationSubscriber.class);
    private static final String CHANNEL = "bhukkad:cache:invalidation";
    private static final int SCAN_BATCH_SIZE = 200;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final LocalCacheService localCacheService;

    public CacheInvalidationSubscriber(RedisTemplate<String, Object> redisTemplate,
                                       ObjectMapper objectMapper,
                                       LocalCacheService localCacheService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.localCacheService = localCacheService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            // The publisher sends the event as a JSON string via RedisTemplate.convertAndSend.
            // GenericJackson2JsonRedisSerializer wraps the String payload in JSON quotes,
            // so we deserialize the body as a String first, then parse the event.
            String json = objectMapper.readValue(message.getBody(), String.class);
            CacheInvalidatedEvent event = objectMapper.readValue(json, CacheInvalidatedEvent.class);
            log.debug("CACHE_INVALIDATION_RECEIVED cacheName={} key={} pattern={}",
                    event.getCacheName(), event.getKey(), event.isPattern());

            String fullKey = event.getKey();
            if (!fullKey.startsWith("bhukkad:")) {
                fullKey = "bhukkad:" + fullKey;
            }

            if (event.isPattern()) {
                // L2: bounded SCAN instead of KEYS (KEYS blocks Redis).
                deletePattern(fullKey + "*");
                // L1: Caffeine cannot evict by prefix; drop the whole bounded
                // local cache (see class javadoc).
                localCacheService.clearAll();
            } else {
                redisTemplate.delete(fullKey);
                localCacheService.invalidate(event.getKey());
            }
        } catch (Exception ex) {
            log.warn("CACHE_INVALIDATION_FAILED error={}", ex.getMessage());
        }
    }

    private void deletePattern(String fullPattern) {
        Set<String> keys = new LinkedHashSet<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(fullPattern).count(SCAN_BATCH_SIZE).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("CACHE_INVALIDATION_SCAN_FAILED pattern={} error={}", fullPattern, e.getMessage());
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    public String getChannel() {
        return CHANNEL;
    }
}
