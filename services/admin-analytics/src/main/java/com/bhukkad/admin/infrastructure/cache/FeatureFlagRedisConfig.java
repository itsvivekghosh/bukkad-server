package com.bhukkad.admin.infrastructure.cache;
import com.bhukkad.admin.domain.service.FeatureFlagService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
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
