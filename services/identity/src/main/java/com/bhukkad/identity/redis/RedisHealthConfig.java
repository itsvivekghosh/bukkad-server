package com.bhukkad.identity.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Replaces Spring Boot's auto-configured {@code RedisHealthIndicator}
 * with a thresholded variant that tolerates brief Redis blips (M5).
 * The readiness group includes this indicator; it only flips to
 * OUT_OF_SERVICE after 3 consecutive failures.
 */
@Configuration
public class RedisHealthConfig {

    @Bean
    public ThresholdedRedisHealthIndicator redisHealthIndicator(RedisConnectionFactory connectionFactory) {
        return new ThresholdedRedisHealthIndicator(connectionFactory);
    }
}
