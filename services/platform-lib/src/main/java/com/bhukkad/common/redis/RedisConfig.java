package com.bhukkad.common.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Shared Redis configuration for the platform library.
 *
 * <p>Services that include {@code spring-boot-starter-data-redis} on the
 * classpath will auto-configure a {@link StringRedisTemplate} suitable for
 * rate-limiting, caching, and pub/sub. Connection details are injected from
 * {@code application.yml} (or environment variables).
 *
 * <p>When {@code spring.data.redis.lettuce.pool.enabled=true} is set in the
 * environment, Spring Boot auto-configures Lettuce connection pooling on the
 * {@link LettuceConnectionFactory} produced here.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.setPassword(password);
        factory.setDatabase(database);
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}
