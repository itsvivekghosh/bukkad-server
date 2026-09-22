package com.bhukkad.social.feed;

import com.bhukkad.social.event.PostCreatedEvent;
import com.bhukkad.common.util.GeohashUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class FeedInvalidationHandler implements MessageListener {

    private static final int GEOHASH_PRECISION = 4;
    private static final String INVALIDATION_CHANNEL = "feed:invalidation";

    private final ObjectProvider<RedisTemplate<String, String>> redisTemplateProvider;
    private final CaffeineCache l1Cache;

    @KafkaListener(topics = "social-events", groupId = "social-invalidation")
    public void onPostCreated(PostCreatedEvent event) {
        RedisTemplate<String, String> redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }

        String geohash = GeohashUtils.encode(event.latitude(), event.longitude(), GEOHASH_PRECISION);
        List<String> affectedCells = GeohashUtils.getNeighbors(geohash);

        affectedCells.forEach(cell -> {
            redisTemplate.convertAndSend(INVALIDATION_CHANNEL, cell);
        });
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        RedisTemplate<String, String> redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }

        String channel = new String(message.getChannel(), java.nio.charset.StandardCharsets.UTF_8);
        if (INVALIDATION_CHANNEL.equals(channel)) {
            String geohash = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            handleInvalidation(geohash);
        }
    }

    public void handleInvalidation(String geohash) {
        RedisTemplate<String, String> redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }

        try {
            List<String> keys = redisTemplate.execute((RedisCallback<List<String>>) connection -> {
                List<String> result = new ArrayList<>();
                try (var cursor = connection.scan(ScanOptions.scanOptions()
                        .match("feed:*:" + geohash + "*")
                        .count(1000)
                        .build())) {
                    while (cursor.hasNext()) {
                        result.add(new String(cursor.next(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                return result;
            });
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.unlink(keys);
            }
        } catch (Exception ex) {
            log.warn("FEED_INVALIDATION_SCAN_FAILED geohash={} error={}", geohash, ex.getMessage());
        }

        l1Cache.invalidate();
        log.debug("FEED_INVALIDATED geohash={}", geohash);
    }
}
