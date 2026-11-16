package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Aspect that records per-command duration histograms for Redis operations
 * executed through {@link com.bhukkad.common.cache.RedisCacheService}.
 *
 * <p>Metrics recorded: {@code redis_command_duration_seconds{command,result}}
 * where {@code command} is one of {@code get}, {@code set}, {@code hGet},
 * {@code hSet}, {@code delete}, {@code increment} and {@code result} is
 * {@code success} or {@code error}.</p>
 *
 * <p>The aspect instruments the same Redis commands that are wrapped by the
 * circuit breakers named {@code redisCacheRead}, {@code redisCacheWrite},
 * {@code redisCacheExists}, {@code redisCacheLock}, {@code redisCacheScan},
 * and {@code redisCacheAdmin} in {@code RedisCacheService}.</p>
 */
@Aspect
@Component
public class RedisCommandMetricsAspect {

    private static final String METRIC_NAME = "redis_command_duration_seconds";

    private final MeterRegistry meterRegistry;

    public RedisCommandMetricsAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Around("execution(* com.bhukkad.common.cache.RedisCacheService.get(..)) || "
            + "execution(* com.bhukkad.common.cache.RedisCacheService.set(..)) || "
            + "execution(* com.bhukkad.common.cache.RedisCacheService.hGet(..)) || "
            + "execution(* com.bhukkad.common.cache.RedisCacheService.hSet(..)) || "
            + "execution(* com.bhukkad.common.cache.RedisCacheService.delete(..)) || "
            + "execution(* com.bhukkad.common.cache.RedisCacheService.increment(..))")
    public Object recordCommandDuration(ProceedingJoinPoint pjp) throws Throwable {
        if (meterRegistry == null) {
            return pjp.proceed();
        }

        String command = pjp.getSignature().getName();
        long startNanos = System.nanoTime();
        try {
            Object result = pjp.proceed();
            recordDuration(command, "success", startNanos);
            return result;
        } catch (Throwable ex) {
            recordDuration(command, "error", startNanos);
            throw ex;
        }
    }

    private void recordDuration(String command, String result, long startNanos) {
        long elapsedNanos = System.nanoTime() - startNanos;
        Timer timer = Timer.builder(METRIC_NAME)
                .tag("command", command)
                .tag("result", result)
                .register(meterRegistry);
        timer.record(Duration.ofNanos(elapsedNanos));
    }
}