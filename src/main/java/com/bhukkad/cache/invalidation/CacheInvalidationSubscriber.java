package com.bhukkad.cache.invalidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bhukkad.cache.event.CacheInvalidatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Listens for cache-invalidation messages published by other instances and
 * evicts the matching entries from the local Redis + Caffeine caches.
 *
 * <p>The companion publisher is {@link DistributedCacheInvalidator}.</p>
 */
@Component
public class CacheInvalidationSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationSubscriber.class);
    private static final String CHANNEL = "bhukkad:cache:invalidation";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public CacheInvalidationSubscriber(RedisTemplate<String, Object> redisTemplate,
                                       ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            CacheInvalidatedEvent event = objectMapper.readValue(message.getBody(), CacheInvalidatedEvent.class);
            log.debug("CACHE_INVALIDATION_RECEIVED cacheName={} key={} pattern={}", event.getCacheName(), event.getKey(), event.isPattern());

            String fullKey = event.getKey();
            if (!fullKey.startsWith("bhukkad:")) {
                fullKey = "bhukkad:" + fullKey;
            }

            if (event.isPattern()) {
                // Delete all matching keys in Redis
                var keys = redisTemplate.keys(fullKey + "*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } else {
                redisTemplate.delete(fullKey);
            }
        } catch (Exception ex) {
            log.warn("CACHE_INVALIDATION_FAILED error={}", ex.getMessage());
        }
    }

    public String getChannel() {
        return CHANNEL;
    }
}
