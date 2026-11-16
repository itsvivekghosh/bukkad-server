package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link RedisCommandMetricsAspect} correctly records
 * per-command duration histograms ({@code redis_command_duration_seconds{command,result}})
 * for RedisCacheService operations.
 */
class RedisCommandMetricsTest {

    private MeterRegistry meterRegistry;
    private RedisCommandMetricsAspect aspect;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        aspect = new RedisCommandMetricsAspect(meterRegistry);
    }

    @Test
    void recordsHistogramForGetCommand() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint("get");
        when(pjp.proceed()).thenReturn("value");

        aspect.recordCommandDuration(pjp);

        Timer timer = meterRegistry.get("redis_command_duration_seconds")
                .tag("command", "get")
                .tag("result", "success")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isGreaterThan(0);
    }

    @Test
    void recordsHistogramForSetCommand() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint("set");
        when(pjp.proceed()).thenReturn(null);

        aspect.recordCommandDuration(pjp);

        Timer timer = meterRegistry.get("redis_command_duration_seconds")
                .tag("command", "set")
                .tag("result", "success")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordsHistogramForHGetCommand() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint("hGet");
        when(pjp.proceed()).thenReturn("value");

        aspect.recordCommandDuration(pjp);

        Timer timer = meterRegistry.get("redis_command_duration_seconds")
                .tag("command", "hGet")
                .tag("result", "success")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordsHistogramForHSetCommand() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint("hSet");
        when(pjp.proceed()).thenReturn(null);

        aspect.recordCommandDuration(pjp);

        Timer timer = meterRegistry.get("redis_command_duration_seconds")
                .tag("command", "hSet")
                .tag("result", "success")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordsHistogramForDeleteCommand() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint("delete");
        when(pjp.proceed()).thenReturn(null);

        aspect.recordCommandDuration(pjp);

        Timer timer = meterRegistry.get("redis_command_duration_seconds")
                .tag("command", "delete")
                .tag("result", "success")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordsHistogramForIncrementCommand() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint("increment");
        when(pjp.proceed()).thenReturn(1L);

        aspect.recordCommandDuration(pjp);

        Timer timer = meterRegistry.get("redis_command_duration_seconds")
                .tag("command", "increment")
                .tag("result", "success")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordsErrorResultWhenExceptionThrown() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint("get");
        RuntimeException ex = new RuntimeException("Redis error");
        when(pjp.proceed()).thenThrow(ex);

        try {
            aspect.recordCommandDuration(pjp);
        } catch (RuntimeException ignored) {
            // expected
        }

        Timer timer = meterRegistry.get("redis_command_duration_seconds")
                .tag("command", "get")
                .tag("result", "error")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

@Test
    void skipsRecordingWhenMeterRegistryIsNull() throws Throwable {
        RedisCommandMetricsAspect noRegistryAspect = new RedisCommandMetricsAspect(null);
        ProceedingJoinPoint pjp = mockJoinPoint("get");
        when(pjp.proceed()).thenReturn("value");

        // Should not throw and should proceed normally
        Object result = noRegistryAspect.recordCommandDuration(pjp);

        assertThat(result).isEqualTo("value");
        // No meter should be recorded since meterRegistry was null
        // (we can't easily verify this without a separate registry, but the key
        // behavior is that no exception is thrown and the call proceeds)
    }

    private ProceedingJoinPoint mockJoinPoint(String methodName) {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getName()).thenReturn(methodName);
        when(pjp.getSignature()).thenReturn(signature);
        return pjp;
    }
}