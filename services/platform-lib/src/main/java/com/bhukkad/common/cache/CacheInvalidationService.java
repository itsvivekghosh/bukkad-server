package com.bhukkad.common.cache;
import com.bhukkad.common.cache.LocalCacheService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class CacheInvalidationService implements MessageListener {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final LocalCacheService localCacheService;
    private final String channel;

    public CacheInvalidationService(StringRedisTemplate stringRedisTemplate,
                                    RedisConnectionFactory connectionFactory,
                                    LocalCacheService localCacheService,
                                    @Value("${app.cache.pubsub-invalidation.channel:bhukkad:cache:invalidate}") String channel) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.connectionFactory = connectionFactory;
        this.localCacheService = localCacheService;
        this.channel = channel;
    }

    public void publishInvalidation(String cacheKey) {
        try {
            stringRedisTemplate.convertAndSend(channel, cacheKey);
            log.debug("CACHE_INVALIDATION_PUBLISHED channel={} key={}", channel, cacheKey);
        } catch (Exception ex) {
            log.warn("CACHE_INVALIDATION_PUBLISH_FAILED channel={} key={} error={}", channel, cacheKey, ex.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String key = new String(message.getBody(), StandardCharsets.UTF_8);
            localCacheService.invalidate(key);
            log.debug("CACHE_INVALIDATION_RECEIVED key={}", key);
        } catch (Exception ex) {
            log.warn("CACHE_INVALIDATION_RECEIVE_FAILED error={}", ex.getMessage());
        }
    }

    @Bean
    public RedisMessageListenerContainer localCacheInvalidationListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(this, new ChannelTopic(channel));
        return container;
    }
}