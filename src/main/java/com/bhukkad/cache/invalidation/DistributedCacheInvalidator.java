package com.bhukkad.cache.invalidation;

import com.bhukkad.cache.event.CacheInvalidatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes cache-invalidation messages to a Redis topic so that other
 * application instances can evict their local copies.
 *
 * <p>The companion subscriber is {@link CacheInvalidationSubscriber}.</p>
 */
@Component
public class DistributedCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(DistributedCacheInvalidator.class);
    private static final String CHANNEL = "bhukkad:cache:invalidation";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public DistributedCacheInvalidator(RedisTemplate<String, Object> redisTemplate,
                                       ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishInvalidation(String cacheName, String key, boolean pattern) {
        try {
            CacheInvalidatedEvent event = new CacheInvalidatedEvent(cacheName, key, pattern);
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(CHANNEL, payload);
            log.debug("CACHE_INVALIDATION_PUBLISHED cacheName={} key={} pattern={}", cacheName, key, pattern);
        } catch (Exception ex) {
            log.warn("CACHE_INVALIDATION_PUBLISH_FAILED error={}", ex.getMessage());
        }
    }
}
