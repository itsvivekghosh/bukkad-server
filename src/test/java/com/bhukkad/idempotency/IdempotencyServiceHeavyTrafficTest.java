package com.bhukkad.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
// Mockito `any()` matchers on the generic RedisTemplate.execute(...) signature
// produce benign unchecked-warning noise in the verify() calls below.
@SuppressWarnings("unchecked")
class IdempotencyServiceHeavyTrafficTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(stringRedisTemplate, new ObjectMapper());
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void tryAcquireLock_success_storesToken() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);

        boolean acquired = service.tryAcquireLock("prefix:", "key123", Duration.ofSeconds(30));

        assertThat(acquired).isTrue();
        verify(valueOps).setIfAbsent(eq("lock:prefix:key123"), anyString(), eq(30000L), any());
    }

    @Test
    void tryAcquireLock_alreadyLocked_returnsFalse() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(false);

        boolean acquired = service.tryAcquireLock("prefix:", "key123", Duration.ofSeconds(30));

        assertThat(acquired).isFalse();
    }

    @Test
    void tryAcquireLock_emptyKey_returnsFalse() {
        boolean acquired = service.tryAcquireLock("prefix:", "", Duration.ofSeconds(30));
        assertThat(acquired).isFalse();
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void releaseLock_usesLuaCompareAndDelete() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        service.tryAcquireLock("p:", "k1", Duration.ofSeconds(10));

        service.releaseLock("p:", "k1");

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), eq(Collections.singletonList("lock:p:k1")), anyString());
    }

    @Test
    void releaseLock_withoutPriorAcquire_doesNotCallLua() {
        // No prior tryAcquireLock, lockTokens empty -> should not call execute
        service.releaseLock("p:", "unknown");

        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any());
    }

    @Test
    void releaseLock_emptyKey_noop() {
        service.releaseLock("p:", "");
        verify(stringRedisTemplate, never()).delete(anyString());
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any());
    }

    @Test
    void releaseLock_doesNotUseSimpleDelete_preventsTheft() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        service.tryAcquireLock("order:", "abc", Duration.ofSeconds(30));
        service.releaseLock("order:", "abc");

        // Must use Lua, not simple delete, to avoid deleting a re-acquired lock
        verify(stringRedisTemplate, never()).delete("lock:order:abc");
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), eq(List.of("lock:order:abc")), anyString());
    }

    @Test
    void concurrentLocks_independentTokens() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        boolean a = service.tryAcquireLock("p:", "a", Duration.ofSeconds(10));
        boolean b = service.tryAcquireLock("p:", "b", Duration.ofSeconds(10));
        assertThat(a).isTrue();
        assertThat(b).isTrue();

        service.releaseLock("p:", "a");
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), eq(Collections.singletonList("lock:p:a")), anyString());

        service.releaseLock("p:", "b");
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), eq(Collections.singletonList("lock:p:b")), anyString());
    }
}
