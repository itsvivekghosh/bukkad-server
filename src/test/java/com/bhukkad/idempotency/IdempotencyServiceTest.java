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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    @Test
    void tryAcquireLock_returnsTrueWhenSetIfAbsentSucceeds() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:pay:key-1"), anyString(),
                eq(300000L), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

        assertTrue(service.tryAcquireLock("pay:", "key-1", Duration.ofMinutes(5)));
    }

    @Test
    void tryAcquireLock_returnsFalseWhenAlreadyLocked() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:pay:key-2"), anyString(),
                eq(300000L), eq(TimeUnit.MILLISECONDS))).thenReturn(false);

        assertFalse(service.tryAcquireLock("pay:", "key-2", Duration.ofMinutes(5)));
    }

    @Test
    void tryAcquireLock_returnsFalseForBlankKey() {
        assertFalse(service.tryAcquireLock("pay:", "  ", Duration.ofMinutes(5)));
        assertFalse(service.tryAcquireLock("pay:", null, Duration.ofMinutes(5)));
    }

    // ===== Batch B: release-lock token path + get/store edge branches =====

    @Test
    void releaseLock_executesUnlockScript_whenTokenHeld() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:pay:key-lua"), anyString(),
                eq(300000L), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

        assertTrue(service.tryAcquireLock("pay:", "key-lua", Duration.ofMinutes(5)));

        when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString())).thenReturn(1L);
        service.releaseLock("pay:", "key-lua");

        verify(stringRedisTemplate).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(Collections.singletonList("lock:pay:key-lua")), anyString());
    }

    @Test
    void releaseLock_unownedLock_isNoop() {
        // Never acquired → no token in the local map → no Redis interaction
        service.releaseLock("pay:", "never-acquired");
        verify(stringRedisTemplate, never())
                .execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), anyString());
    }

    @Test
    void releaseLock_redisFailure_isSwallowed() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:pay:key-err"), anyString(),
                eq(300000L), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        assertTrue(service.tryAcquireLock("pay:", "key-err", Duration.ofMinutes(5)));

        when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(), anyString())).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> service.releaseLock("pay:", "key-err"));
    }

    @Test
    void get_blankKey_returnsEmpty() {
        assertFalse(service.getOrderResult("", TestResult.class).isPresent());
        assertFalse(service.getOrderResult(null, TestResult.class).isPresent());
    }

    @Test
    void get_malformedPayload_returnsEmpty() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("idempotency:order:bad-json")).thenReturn("not-json{");

        Optional<TestResult> result = service.getOrderResult("bad-json", TestResult.class);

        assertFalse(result.isPresent());
    }

    @Test
    void store_nullResult_skipsWrite() {
        // Null result short-circuits before any Redis interaction, so no stubbing
        // of the template is needed (the service must never reach opsForValue()).
        service.storeOrderResult(KEY, null, Duration.ofMinutes(5));

        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void store_serializationFailure_isSwallowed() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper failingMapper =
                org.mockito.Mockito.mock(com.fasterxml.jackson.databind.ObjectMapper.class);
        IdempotencyService svc = new IdempotencyService(stringRedisTemplate, failingMapper);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("no serializer") {});
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        assertDoesNotThrow(() -> svc.storePaymentResult(KEY, new TestResult("x"), Duration.ofMinutes(5)));
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void tryAcquireLock_propagatesRedisFailure() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RuntimeException("redis down"));

        // The lock utility propagates Redis errors; the caller
        // (PaymentIdempotencyService) decides whether to fail open or closed.
        assertThrows(RuntimeException.class,
                () -> service.tryAcquireLock("pay:", "key-3", Duration.ofMinutes(5)));
    }

    @Test
    void releaseLock_deletesKey() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        // Acquire first to store token, then release should use Lua
        service.tryAcquireLock("pay:", "key-4", Duration.ofMinutes(5));
        service.releaseLock("pay:", "key-4");

        verify(stringRedisTemplate).execute(any(org.springframework.data.redis.core.script.DefaultRedisScript.class),
                eq(java.util.Collections.singletonList("lock:pay:key-4")), anyString());
    }

    @Test
    void releaseLock_ignoresBlankKey() {
        service.releaseLock("pay:", null);
        service.releaseLock("pay:", "");
        // No exception and no Redis interaction expected.
    }

    record TestResult(String result) {}
}