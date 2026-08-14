package com.bhukkad.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RedisConfigTest {

    private RedisConfig redisConfig;

    @BeforeEach
    void setUp() {
        redisConfig = new RedisConfig();
        ReflectionTestUtils.setField(redisConfig, "restaurantTtl", 1800L);
        ReflectionTestUtils.setField(redisConfig, "restaurantListTtl", 600L);
        ReflectionTestUtils.setField(redisConfig, "menuItemTtl", 900L);
        ReflectionTestUtils.setField(redisConfig, "menuCategoryTtl", 1800L);
        ReflectionTestUtils.setField(redisConfig, "cuisineTtl", 86400L);
        ReflectionTestUtils.setField(redisConfig, "userProfileTtl", 3600L);
        ReflectionTestUtils.setField(redisConfig, "cartTtl", 1800L);
        ReflectionTestUtils.setField(redisConfig, "orderTtl", 300L);
        ReflectionTestUtils.setField(redisConfig, "reviewTtl", 1800L);
        ReflectionTestUtils.setField(redisConfig, "couponTtl", 3600L);
        ReflectionTestUtils.setField(redisConfig, "searchTtl", 300L);
    }

    @Test
    void objectMapper_disablesTimestampsAndUnknownProperties() {
        ObjectMapper mapper = redisConfig.objectMapper();

        assertNotNull(mapper);
        assertFalse(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    @Test
    void redisTemplate_usesProvidedConnectionFactory() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);

        RedisTemplate<String, Object> template = redisConfig.redisTemplate(factory);

        assertNotNull(template);
        assertSame(factory, template.getConnectionFactory());
        assertNotNull(template.getKeySerializer());
        assertNotNull(template.getValueSerializer());
        assertNotNull(template.getHashKeySerializer());
        assertNotNull(template.getHashValueSerializer());
    }

    @Test
    void cacheManager_buildsWithConfiguredTtls() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);

        CacheManager cacheManager = redisConfig.cacheManager(factory);

        assertNotNull(cacheManager);
        assertNotNull(cacheManager.getCache("restaurant"));
        assertNotNull(cacheManager.getCache("cart"));
        assertNotNull(cacheManager.getCache("coupon-list"));
        assertNotNull(cacheManager.getCache("recommended"));
    }
}
