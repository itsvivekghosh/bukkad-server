package com.bhukkad.config;

import com.bhukkad.cluster.ClusterProperties;
import com.bhukkad.live.OrderLiveRedisSubscriber;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@EnableConfigurationProperties(ClusterProperties.class)
public class ClusterConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.cluster.live-relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    RedisMessageListenerContainer orderLiveRelayListenerContainer(
            RedisConnectionFactory connectionFactory,
            OrderLiveRedisSubscriber subscriber,
            ClusterProperties clusterProperties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                subscriber,
                new ChannelTopic(clusterProperties.getLiveRelay().getChannel()));
        return container;
    }
}
