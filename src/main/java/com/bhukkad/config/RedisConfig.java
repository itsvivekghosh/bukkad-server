package com.bhukkad.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig {

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

    /**
     * Primary ObjectMapper for API responses - NO type info
     * This is used by Spring MVC for REST responses
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // NO DefaultTyping - clean JSON output
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

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .transactionAware()
                .build();
    }
}