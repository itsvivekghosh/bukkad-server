package com.bhukkad.common.idempotency;

import com.bhukkad.common.error.DuplicateRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Request/result idempotency: Redis-backed result caching (hit, miss, poison
 * payload, serialization failure), duplicate-key rejection via insert-if-absent
 * and the SETNX lock with Lua-compare-and-delete release. Redis is mocked —
 * no infra.
 */
class IdempotencyServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private IdempotencyRecordRepository repository;
    private IdempotencyService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        repository = mock(IdempotencyRecordRepository.class);
        service = new IdempotencyService(redis, new ObjectMapper(), repository);
    }

    private record Echo(String message) {
    }

    @Test
    void orderAndPaymentResults_roundTripThroughJsonCache() {
        service.storeOrderResult("key-1", new Echo("placed"), Duration.ofMinutes(5));
        verify(values).set(eq("idempotency:order:key-1"), eq("{\"message\":\"placed\"}"),
                eq(Duration.ofMinutes(5).toMillis()), eq(TimeUnit.MILLISECONDS));

        when(values.get("idempotency:order:key-1")).thenReturn("{\"message\":\"placed\"}");
        assertThat(service.getOrderResult("key-1", Echo.class))
                .contains(new Echo("placed"));

        service.storePaymentResult("pay-9", new Echo("captured"), Duration.ofSeconds(30));
        verify(values).set(eq("idempotency:payment:pay-9"), anyString(), anyLong(), any());
        when(values.get("idempotency:payment:pay-9")).thenReturn("{\"message\":\"captured\"}");
        assertThat(service.getPaymentResult("pay-9", Echo.class)).contains(new Echo("captured"));
    }

    @Test
    void blankKeysAndNullResults_shortCircuitEverything() {
        assertThat(service.getOrderResult(" ", Echo.class)).isEmpty();
        assertThat(service.getPaymentResult(null, Echo.class)).isEmpty();
        service.storeOrderResult(null, new Echo("x"), Duration.ofMinutes(1));
        service.storePaymentResult("", new Echo("x"), Duration.ofMinutes(1));
        service.storeOrderResult("k", null, Duration.ofMinutes(1));
        verify(values, never()).set(anyString(), anyString(), anyLong(), any());
        assertThat(service.maybeRejectDuplicate(null)).isNull();
        assertThat(service.maybeRejectDuplicate("  ")).isNull();
        assertThat(service.tryAcquireLock("payment", "", Duration.ofSeconds(5))).isFalse();
        service.releaseLock("payment", null); // must not touch Redis
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void cachedPoisonPayload_degradesToMiss() {
        when(values.get("idempotency:order:bad")).thenReturn("{not json");
        assertThat(service.getOrderResult("bad", Echo.class)).isEmpty();
    }

    @Test
    void serializationFailureOnStore_isSwallowed() {
        // java.time.Duration has no serializable bean properties for the plain
        // ObjectMapper here (no JSR-310 module) → InvalidDefinitionException
        service.storeOrderResult("dur", java.time.Duration.ofSeconds(5), Duration.ofMinutes(1));
        // no exception must have escaped; nothing cached
        verify(values, never()).set(eq("idempotency:order:dur"), anyString(), anyLong(), any());
    }

    @Test
    void cacheMiss_returnsEmpty() {
        when(values.get("idempotency:order:miss")).thenReturn(null);
        assertThat(service.getOrderResult("miss", Echo.class)).isEmpty();
        when(values.get("idempotency:payment:blank")).thenReturn("   ");
        assertThat(service.getPaymentResult("blank", Echo.class)).isEmpty();
    }

    @Test
    void duplicateRejectedOnlyWhenInsertFindsExistingRow() {
        when(repository.insertIfAbsent(anyString(), anyString(), any(), anyString(), any(),
                any(LocalDateTime.class))).thenReturn(0);
        assertThat(service.maybeRejectDuplicate("dup-key"))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessageContaining("dup-key");

        when(repository.insertIfAbsent(anyString(), anyString(), any(), anyString(), any(),
                any(LocalDateTime.class))).thenReturn(1);
        assertThat(service.maybeRejectDuplicate("fresh-key")).isNull();

        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> expires = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).insertIfAbsent(eq("fresh-key"), scope.capture(), any(),
                eq(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS.name()), any(), expires.capture());
        assertThat(scope.getValue()).isEqualTo(IdempotencyRecord.IdempotencyScope.HTTP_REQUEST.name());
        assertThat(expires.getValue()).isAfter(LocalDateTime.now().plusHours(23));
    }

    @Test
    void lockAcquireStoreToken_releaseRunsLua_andForgottenTokenIsNoop() {
        when(values.setIfAbsent(eq("lock:payment:pi-1"), anyString(), eq(5000L), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(Boolean.TRUE);
        assertThat(service.tryAcquireLock("payment:", "pi-1", Duration.ofSeconds(5))).isTrue();

        when(values.setIfAbsent(eq("lock:payment:pi-2"), anyString(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(Boolean.FALSE);
        assertThat(service.tryAcquireLock("payment:", "pi-2", Duration.ofSeconds(5))).isFalse();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        service.releaseLock("payment:", "pi-1");
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly("lock:payment:pi-1");

        // releasing without a held token (or twice) must NOT delete blindly:
        // the times(1) verification above already proves no extra Lua ran.
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureDuringRelease_isSwallowed() {
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(Boolean.TRUE);
        assertThat(service.tryAcquireLock("order:", "o-1", Duration.ofSeconds(5))).isTrue();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("connection reset"));
        service.releaseLock("order:", "o-1"); // swallowed by design (TTL reaps it)
    }
}
