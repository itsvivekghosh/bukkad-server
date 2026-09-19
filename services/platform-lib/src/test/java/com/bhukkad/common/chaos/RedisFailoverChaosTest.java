package com.bhukkad.common.chaos;

import com.bhukkad.common.ratelimit.RateLimitDecision;
import com.bhukkad.common.ratelimit.RateLimitProperties;
import com.bhukkad.common.ratelimit.RedisRateLimitService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisFailoverChaosTest {

    private static RedisRateLimitService service(StringRedisTemplate template,
                                                 boolean failOpen,
                                                 MeterRegistry meterRegistry) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setFailOpenOnRedisError(failOpen);
        return new RedisRateLimitService(template, properties, providerOf(meterRegistry),
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults());
    }

    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public MeterRegistry getObject() { return registry; }
            @Override
            public MeterRegistry getObject(Object... args) { return registry; }
            @Override
            public MeterRegistry getIfAvailable() { return registry; }
            @Override
            public MeterRegistry getIfUnique() { return registry; }
        };
    }

    @Test
    void whenRedisIsDown_failOpen_allowsRequestAndIncrementsBypassCounter() {
        StringRedisTemplate deadRedis = mock(StringRedisTemplate.class);
        when(deadRedis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                any(java.util.List.class), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Redis cluster failover in progress"));

        RateLimitDecision decision = service(deadRedis, true, new SimpleMeterRegistry())
                .check("chaos-bucket", "user:1", 10, 60);

        assertThat(decision.allowed()).as("fail-open: request must be allowed when Redis is down").isTrue();
        assertThat(decision.count()).isEqualTo(0);
    }

    @Test
    void whenRedisIsDown_failClosed_deniesRequestAndRecordsRetryAfter() {
        StringRedisTemplate deadRedis = mock(StringRedisTemplate.class);
        when(deadRedis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                any(java.util.List.class), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Redis cluster failover in progress"));

        RateLimitDecision decision = service(deadRedis, false, new SimpleMeterRegistry())
                .check("chaos-bucket", "user:1", 10, 60);

        assertThat(decision.allowed()).as("fail-closed: request must be denied when Redis is down").isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void whenRedisIsDown_bypassCounterIsIncremented() {
        StringRedisTemplate deadRedis = mock(StringRedisTemplate.class);
        when(deadRedis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                any(java.util.List.class), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Redis cluster failover in progress"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        service(deadRedis, true, meterRegistry)
                .check("chaos-bucket", "user:1", 10, 60);
        service(deadRedis, true, meterRegistry)
                .check("chaos-bucket", "user:2", 10, 60);

        assertThat(meterRegistry.get(RedisRateLimitService.METRIC_BYPASS_REDIS_ERROR)
                .tag("bucket", "chaos-bucket").counter().count()).isEqualTo(2.0);
    }

    @Test
    void whenRedisRecovers_afterFailOpen_normalOperationResumes() {
        StringRedisTemplate recoveringRedis = mock(StringRedisTemplate.class);
        when(recoveringRedis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                any(java.util.List.class), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("failover"))
                .thenReturn(1L);

        var service = service(recoveringRedis, true, new SimpleMeterRegistry());

        RateLimitDecision failOpenDecision = service.check("chaos-bucket", "user:1", 10, 60);
        assertThat(failOpenDecision.allowed()).isTrue();

        RateLimitDecision recoveredDecision = service.check("chaos-bucket", "user:1", 10, 60);
        assertThat(recoveredDecision.allowed()).isTrue();
        assertThat(recoveredDecision.count()).isEqualTo(1);
    }
}
