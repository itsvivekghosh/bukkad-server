package com.bhukkad.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private IdempotencyService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String KEY = "test-key";
    private static final String PAYLOAD = "{\"result\":\"ok\"}";

    @BeforeEach
    void setUp() {
        // Use reflection to inject ObjectMapper since @InjectMocks won't auto-wire it
        service = new IdempotencyService(stringRedisTemplate, new ObjectMapper());
    }

    @Test
    void getOrderResult_returnsEmpty_whenKeyIsBlank() {
        assertFalse(service.getOrderResult("", String.class).isPresent());
        assertFalse(service.getOrderResult(null, String.class).isPresent());
    }

    @Test
    void getOrderResult_returnsEmpty_whenRedisMiss() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        Optional<String> result = service.getOrderResult(KEY, String.class);
        assertFalse(result.isPresent());
    }

    @Test
    void getOrderResult_returnsValue_whenRedisHit() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("idempotency:order:" + KEY)).thenReturn(PAYLOAD);

        Optional<TestResult> result = service.getOrderResult(KEY, TestResult.class);
        assertTrue(result.isPresent());
        assertEquals("ok", result.get().result);
    }

    @Test
    void storeOrderResult_skips_whenKeyIsBlank() {
        service.storeOrderResult("", "data", Duration.ofMinutes(5));
        service.storeOrderResult(null, "data", Duration.ofMinutes(5));
    }

    @Test
    void storeOrderResult_skips_whenResultIsNull() {
        service.storeOrderResult(KEY, null, Duration.ofMinutes(5));
    }

    @Test
    void storeOrderResult_storesInRedis() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        service.storeOrderResult(KEY, new TestResult("ok"), Duration.ofMinutes(5));

        verify(valueOps).set(eq("idempotency:order:" + KEY), anyString(), eq(300000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void getPaymentResult_usesPaymentPrefix() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("idempotency:payment:" + KEY)).thenReturn(PAYLOAD);

        Optional<TestResult> result = service.getPaymentResult(KEY, TestResult.class);
        assertTrue(result.isPresent());
    }

    @Test
    void storePaymentResult_usesPaymentPrefix() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        service.storePaymentResult(KEY, new TestResult("ok"), Duration.ofMinutes(5));

        verify(valueOps).set(eq("idempotency:payment:" + KEY), anyString(), eq(300000L), eq(TimeUnit.MILLISECONDS));
    }

    record TestResult(String result) {}
}