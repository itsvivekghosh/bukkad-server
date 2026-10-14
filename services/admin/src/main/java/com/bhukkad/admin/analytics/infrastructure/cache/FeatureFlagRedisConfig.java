package com.bhukkad.admin.analytics.infrastructure.cache;

import com.bhukkad.admin.analytics.domain.service.FeatureFlagService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Registers the {@link FeatureFlagService} as a Redis pub/sub listener on the
 * feature-flag change channel so every replica evicts its local override cache
 * when any replica toggles a flag.
 */
@Configuration
public class FeatureFlagRedisConfig {

    /**
     * @Lazy is REQUIRED: as a SmartLifecycle the container would otherwise be
     * started during context refresh, and its synchronous subscribe kills boot
     * when Redis is unreachable ("Failed to start bean
     * 'featureFlagListenerContainer'"). FeatureFlagListenerStarter creates it
     * after the application is ready and retries until Redis answers.
     */
    @Bean
    @Lazy
    RedisMessageListenerContainer featureFlagListenerContainer(RedisConnectionFactory connectionFactory,
                                                               FeatureFlagService featureFlagService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                featureFlagService,
                new ChannelTopic(FeatureFlagService.CHANGED_CHANNEL));
        return container;
    }
}