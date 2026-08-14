package com.bhukkad.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private RateLimitProperties properties;
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        rateLimitService = new RateLimitService(stringRedisTemplate, properties);
    }

    @Test
    void check_allowsRequestsUnderLimit() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(3L);

        RateLimitDecision decision = rateLimitService.check("order-track", "user:1:order:9");

        assertTrue(decision.allowed());
        assertEquals(3, decision.currentCount());
        assertEquals(20, decision.limit());
    }

    @Test
    void check_deniesRequestsOverLimit() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(21L);
        when(stringRedisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(42L);

        RateLimitDecision decision = rateLimitService.check("order-track", "user:1:order:9");

        assertFalse(decision.allowed());
        assertEquals(42, decision.retryAfterSeconds());
    }

    @Test
    void check_whenDisabled_alwaysAllows() {
        properties.setEnabled(false);

        RateLimitDecision decision = rateLimitService.check("order-track", "user:1:order:9");

        assertTrue(decision.allowed());
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void check_whenRedisFails_failsOpen() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        RateLimitDecision decision = rateLimitService.check("kitchen-queue", "user:2:restaurant:5");

        assertTrue(decision.allowed());
    }
}
