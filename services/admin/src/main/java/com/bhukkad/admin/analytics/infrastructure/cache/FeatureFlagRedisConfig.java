package com.bhukkad.admin.analytics.infrastructure.cache;

import com.bhukkad.admin.analytics.domain.service.FeatureFlagService;
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

    /**
     * Never auto-start during context refresh: container.start() subscribes
     * synchronously, so a Redis outage at boot cancels the whole refresh and
     * kills the service ("Failed to start bean 'featureFlagListenerContainer'").
     * FeatureFlagListenerStarter starts it after ApplicationReady and retries
     * until Redis is reachable — flag hot-reload is best-effort. Same pattern
     * as the platform's localCacheInvalidationListenerContainer (this Spring
     * Data version has no setAutoStartup setter, hence the override).
     */
    @Bean
    RedisMessageListenerContainer featureFlagListenerContainer(RedisConnectionFactory connectionFactory,
                                                               FeatureFlagService featureFlagService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                featureFlagService,
                new ChannelTopic(FeatureFlagService.CHANGED_CHANNEL));
        return container;
    }
}