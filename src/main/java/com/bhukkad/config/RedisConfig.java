package com.bhukkad.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.bhukkad.common.cache.CacheInvalidationSubscriber;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(RedisProperties.class)
@EnableCaching
public class RedisConfig {

    /**
     * Bounds the Redis TCP connect + command timeouts. Without an explicit
     * socket connect timeout, the OS default (often 60s+) makes connection
     * creation block while holding the connection factory's shared-connection
     * lock — every other Redis operation hangs behind it. 2s socket + 2s
     * command keeps startup and degraded paths fast and fail-open (see
     * ClientEncryptionKeyService).
     *
     * <p>This bean replaces Spring Boot's auto-configured factory so the
     * socket options are applied. Pool config is derived from
     * {@link RedisProperties} so application.yml {@code spring.data.redis}
     * and {@code spring.data.redis.lettuce.pool} are respected.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory(RedisProperties props) {
        org.springframework.data.redis.connection.RedisConfiguration configuration;
        if (props.getSentinel() != null && org.springframework.util.StringUtils.hasText(props.getSentinel().getMaster())
                && props.getSentinel().getNodes() != null && !props.getSentinel().getNodes().isEmpty()) {
            // Sentinel failover mode: master name + node list (host:port pairs).
            org.springframework.data.redis.connection.RedisSentinelConfiguration sentinel =
                    new org.springframework.data.redis.connection.RedisSentinelConfiguration(
                            props.getSentinel().getMaster(),
                            new java.util.LinkedHashSet<>(props.getSentinel().getNodes()));
            sentinel.setPassword(org.springframework.data.redis.connection.RedisPassword.of(props.getPassword()));
            sentinel.setDatabase(props.getDatabase());
            configuration = sentinel;
        } else {
            org.springframework.data.redis.connection.RedisStandaloneConfiguration standalone =
                    new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                            props.getHost(), props.getPort());
            standalone.setDatabase(props.getDatabase());
            standalone.setPassword(org.springframework.data.redis.connection.RedisPassword.of(props.getPassword()));
            configuration = standalone;
        }

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build())
                .build();

        org.apache.commons.pool2.impl.GenericObjectPoolConfig<?> poolConfig =
                new org.apache.commons.pool2.impl.GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(props.getLettuce().getPool().getMaxActive());
        poolConfig.setMaxIdle(props.getLettuce().getPool().getMaxIdle());
        poolConfig.setMinIdle(props.getLettuce().getPool().getMinIdle());
        poolConfig.setMaxWait(props.getLettuce().getPool().getMaxWait());

        LettucePoolingClientConfiguration pool = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .clientOptions(clientOptions)
                .commandTimeout(props.getTimeout())
                .build();

        return new LettuceConnectionFactory(configuration, pool);
    }

    @Value("${cache.ttl.restaurant:1800}")
    private long restaurantTtl;

    @Value("${cache.ttl.restaurant-list:600}")
    private long restaurantListTtl;

    @Value("${cache.ttl.menu-item:900}")
    private long menuItemTtl;

    @Value("${cache.ttl.menu-category:1800}")
    private long menuCategoryTtl;

    @Value("${cache.ttl.cuisine:86400}")
    private long cuisineTtl;

    @Value("${cache.ttl.user-profile:3600}")
    private long userProfileTtl;

    @Value("${cache.ttl.cart:1800}")
    private long cartTtl;

    @Value("${cache.ttl.order:300}")
    private long orderTtl;

    @Value("${cache.ttl.review:1800}")
    private long reviewTtl;

    @Value("${cache.ttl.coupon:3600}")
    private long couponTtl;

    @Value("${cache.ttl.search:300}")
    private long searchTtl;

    @Value("${cache.ttl.home-feed:60}")
    private long homeFeedTtl;

    @Value("${cache.ttl.serviceability:60}")
    private long serviceabilityTtl;

    @Value("${cache.ttl.admin-dashboard:60}")
    private long adminDashboardTtl;

    /**
     * Primary ObjectMapper for API responses - NO type info
     * This is used by Spring MVC for REST responses.
     *
     * <p>The {@code bhukkadFieldSelection} filter is registered as a no-op so
     * any DTO can be wrapped with {@link com.bhukkad.common.web.FieldProjection#project}
     * without first being annotated with {@code @JsonFilter}. DTOs that want
     * to be projectable via {@code ?fields=} still need
     * {@code @JsonFilter("bhukkadFieldSelection")} on their class declaration.
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // NO DefaultTyping - clean JSON output
        SimpleFilterProvider filters = new SimpleFilterProvider()
                .addFilter(com.bhukkad.common.web.FieldProjection.FILTER_ID,
                        SimpleBeanPropertyFilter.serializeAll());
        mapper.setFilterProvider(filters);
        return mapper;
    }

    /**
     * Separate ObjectMapper for Redis - WITH type info
     * This is ONLY used for Redis serialization/deserialization
     */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // Type info needed for Redis to deserialize correctly
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use Redis-specific ObjectMapper
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.setDefaultSerializer(jsonSerializer);
        template.afterPropertiesSet();

        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(jsonSerializer))
                .prefixCacheNameWith("bhukkad:")
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("restaurant", defaultConfig.entryTtl(Duration.ofSeconds(restaurantTtl)));
        cacheConfigs.put("restaurant-list", defaultConfig.entryTtl(Duration.ofSeconds(restaurantListTtl)));
        cacheConfigs.put("restaurant-search", defaultConfig.entryTtl(Duration.ofSeconds(searchTtl)));
        cacheConfigs.put("restaurant-filter", defaultConfig.entryTtl(Duration.ofSeconds(searchTtl)));
        cacheConfigs.put("menu-item", defaultConfig.entryTtl(Duration.ofSeconds(menuItemTtl)));
        cacheConfigs.put("menu-item-list", defaultConfig.entryTtl(Duration.ofSeconds(menuItemTtl)));
        cacheConfigs.put("menu-category", defaultConfig.entryTtl(Duration.ofSeconds(menuCategoryTtl)));
        cacheConfigs.put("menu-category-list", defaultConfig.entryTtl(Duration.ofSeconds(menuCategoryTtl)));
        cacheConfigs.put("cuisine", defaultConfig.entryTtl(Duration.ofSeconds(cuisineTtl)));
        cacheConfigs.put("cuisine-list", defaultConfig.entryTtl(Duration.ofSeconds(cuisineTtl)));
        cacheConfigs.put("user-profile", defaultConfig.entryTtl(Duration.ofSeconds(userProfileTtl)));
        cacheConfigs.put("cart", defaultConfig.entryTtl(Duration.ofSeconds(cartTtl)));
        cacheConfigs.put("order", defaultConfig.entryTtl(Duration.ofSeconds(orderTtl)));
        cacheConfigs.put("order-list", defaultConfig.entryTtl(Duration.ofSeconds(orderTtl)));
        cacheConfigs.put("order-track", defaultConfig.entryTtl(Duration.ofSeconds(30)));
        cacheConfigs.put("kitchen-queue", defaultConfig.entryTtl(Duration.ofSeconds(15)));
        cacheConfigs.put("review", defaultConfig.entryTtl(Duration.ofSeconds(reviewTtl)));
        cacheConfigs.put("review-list", defaultConfig.entryTtl(Duration.ofSeconds(reviewTtl)));
        cacheConfigs.put("coupon", defaultConfig.entryTtl(Duration.ofSeconds(couponTtl)));
        cacheConfigs.put("coupon-list", defaultConfig.entryTtl(Duration.ofSeconds(couponTtl)));
        cacheConfigs.put("bestseller", defaultConfig.entryTtl(Duration.ofSeconds(menuItemTtl)));
        cacheConfigs.put("recommended", defaultConfig.entryTtl(Duration.ofSeconds(menuItemTtl)));
        cacheConfigs.put("home-feed", defaultConfig.entryTtl(Duration.ofSeconds(homeFeedTtl)));
        cacheConfigs.put("serviceability", defaultConfig.entryTtl(Duration.ofSeconds(serviceabilityTtl)));
        cacheConfigs.put("admin", defaultConfig.entryTtl(Duration.ofSeconds(adminDashboardTtl)));
        cacheConfigs.put("admin-dashboard", defaultConfig.entryTtl(Duration.ofSeconds(adminDashboardTtl)));
        cacheConfigs.put("restaurant-nearby", defaultConfig.entryTtl(Duration.ofSeconds(300)));
        cacheConfigs.put("menu-search", defaultConfig.entryTtl(Duration.ofSeconds(searchTtl)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .transactionAware()
                .build();
    }

    @Bean
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheInvalidationSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(subscriber.getChannel()));
        return container;
    }
}