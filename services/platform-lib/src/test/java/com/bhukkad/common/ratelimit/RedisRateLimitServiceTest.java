package com.bhukkad.common.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the V-18 fixes: ONE atomic EVALsha of the INCR+PEXPIRE+limit script
 * (no post-INCR EXPIRE command that can race or be lost), negative reply =
 * denied with retry-after, and an observable fail-open on Redis connection
 * errors.
 */
class RedisRateLimitServiceTest {

    @SuppressWarnings("unchecked")
    private static void stubScriptResult(StringRedisTemplate template, Long result) {
        doReturn(result).when(template).execute(any(RedisScript.class), anyList(), any(), any());
    }

    private static RedisScript<Long> captureExecutedScript(StringRedisTemplate template) {
        ArgumentCaptor<RedisScript<Long>> captor = ArgumentCaptor.forClass(RedisScript.class);
        verify(template).execute(captor.capture(), anyList(), any(), any());
        return captor.getValue();
    }

    private static RedisRateLimitService service(StringRedisTemplate template,
                                                 boolean failOpen,
                                                 SimpleMeterRegistry meterRegistry) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setFailOpenOnRedisError(failOpen);
        return new RedisRateLimitService(template, properties, providerOf(meterRegistry));
    }

    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> providerOf(
            io.micrometer.core.instrument.MeterRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public io.micrometer.core.instrument.MeterRegistry getObject() {
                return registry;
            }

            @Override
            public io.micrometer.core.instrument.MeterRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public io.micrometer.core.instrument.MeterRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public io.micrometer.core.instrument.MeterRegistry getIfUnique() {
                return registry;
            }
        };
    }

    @Test
    void decision_isOneAtomicScriptCall_neverIncrPlusExpire() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        stubScriptResult(template, 3L);

        RateLimitDecision decision = service(template, true, new SimpleMeterRegistry())
                .check("search", "user:1", 10, 60);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.count()).isEqualTo(3);

        // The heart of V-18: exactly ONE script round trip; the template's
        // value/expire APIs must never be touched at all (no INCR+EXPIRE race).
        verify(template, never()).opsForValue();
        verify(template, never()).expire(any(String.class), anyLong(), any());
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(template).execute(any(RedisScript.class), keys.capture(),
                eq("60000"), eq("10"));
        assertThat(keys.getValue()).containsExactly("bhukkad:ratelimit:search:user:1");
    }

    @Test
    void luaScript_containsIncrPexireAndLimitCompare_inOneBlock() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        stubScriptResult(template, 1L);
        service(template, true, new SimpleMeterRegistry()).check("order-track", "ip:1", 20, 60);

        String script = captureExecutedScript(template).getScriptAsString();
        assertThat(script).contains("INCR");
        assertThat(script).contains("PEXPIRE");
        assertThat(script).contains("PTTL");
        // PEXPIRE happens in the same script as INCR — a crash between the two
        // can no longer strand the key without a TTL.
        assertThat(script.indexOf("PEXPIRE")).isGreaterThan(script.indexOf("INCR"));
    }

    @Test
    void denied_negativeReply_becomesRetryAfterSeconds() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        stubScriptResult(template, -2500L); // 2.5 s of window left

        RateLimitDecision decision = service(template, true, new SimpleMeterRegistry())
                .check("search", "user:9", 10, 60);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(3); // ceil(2500ms)
    }

    @Test
    void redisConnectionFailure_failOpen_allowsAndCountsBypass() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        RateLimitDecision decision = service(template, true, meterRegistry)
                .check("search", "user:9", 10, 60);

        assertThat(decision.allowed()).isTrue();
        assertThat(meterRegistry.get(RedisRateLimitService.METRIC_BYPASS_REDIS_ERROR)
                .tag("bucket", "search").counter().count()).isEqualTo(1.0);
    }

    @Test
    void redisConnectionFailure_failClosed_deniesForWindow() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        RateLimitDecision decision = service(template, false, new SimpleMeterRegistry())
                .check("search", "user:9", 10, 60);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void lettuceSystemTransportError_alsoRoutesThroughBypass() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RedisSystemException("command timed out in transport",
                        new java.util.concurrent.TimeoutException("waited too long")));

        RateLimitDecision decision = service(template, true, new SimpleMeterRegistry())
                .check("search", "user:9", 10, 60);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void nullReply_failOpenTreatedAsAllowed() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        stubScriptResult(template, null);

        RateLimitDecision decision = service(template, true, new SimpleMeterRegistry())
                .check("search", "user:9", 10, 60);

        assertThat(decision.allowed()).isTrue();
    }
}
