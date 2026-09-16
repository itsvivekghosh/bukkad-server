package com.bhukkad.common.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared Redis configuration for the platform library.
 *
 * <p>Services that include {@code spring-boot-starter-data-redis} on the
 * classpath will auto-configure a {@link StringRedisTemplate} suitable for
 * rate-limiting, caching, and pub/sub. Connection details are injected from
 * {@code application.yml} (or environment variables).
 *
 * <p>Supports three deployment modes:
 * <ul>
 *   <li><strong>Standalone (default):</strong> single Redis instance via
 *       {@code spring.data.redis.host} + {@code spring.data.redis.port}</li>
 *   <li><strong>Sentinel:</strong> configure {@code spring.data.redis.sentinel.master}
 *       and {@code spring.data.redis.sentinel.nodes}</li>
 *   <li><strong>Cluster:</strong> configure {@code spring.data.redis.cluster.nodes}</li>
 * </ul>
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

    @Value("${spring.data.redis.sentinel.master:}")
    private String sentinelMaster;

    @Value("${spring.data.redis.sentinel.nodes:}")
    private List<String> sentinelNodes;

    @Value("${spring.data.redis.cluster.nodes:}")
    private List<String> clusterNodes;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // Priority: cluster > sentinel > standalone
        if (clusterNodes != null && !clusterNodes.isEmpty()) {
            RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(clusterNodes);
            LettuceConnectionFactory factory = new LettuceConnectionFactory(clusterConfig);
            factory.setPassword(password);
            return factory;
        }

        if (sentinelMaster != null && !sentinelMaster.isBlank()
                && sentinelNodes != null && !sentinelNodes.isEmpty()) {
            Set<String> sentinelNodeSet = sentinelNodes.stream().collect(Collectors.toSet());
            RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration(sentinelMaster, sentinelNodeSet);
            LettuceConnectionFactory factory = new LettuceConnectionFactory(sentinelConfig);
            factory.setPassword(password);
            factory.setDatabase(database);
            return factory;
        }

        // Standalone
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
