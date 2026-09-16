package com.bhukkad.identity.redis;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thresholded Redis health indicator for identity (M5).
 *
 * <p>Prevents readiness flaps on brief Redis blips by requiring
 * {@code failureThreshold} consecutive failures before reporting
 * {@code OUT_OF_SERVICE}. This stops HPA scale-down/up churn when
 * Redis latency spikes for < 5s.</p>
 */
public class ThresholdedRedisHealthIndicator implements HealthIndicator {

    private static final int DEFAULT_FAILURE_THRESHOLD = 3;

    private final RedisConnectionFactory connectionFactory;
    private final int failureThreshold;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public ThresholdedRedisHealthIndicator(RedisConnectionFactory connectionFactory) {
        this(connectionFactory, DEFAULT_FAILURE_THRESHOLD);
    }

    public ThresholdedRedisHealthIndicator(RedisConnectionFactory connectionFactory,
                                           int failureThreshold) {
        this.connectionFactory = connectionFactory;
        this.failureThreshold = failureThreshold;
    }

    @Override
    public Health health() {
        try {
            if (connectionFactory == null) {
                consecutiveFailures.incrementAndGet();
                return Health.outOfService()
                        .withDetail("error", "no connection factory")
                        .build();
            }
            connectionFactory.getConnection().ping();
            consecutiveFailures.set(0);
            return Health.up().build();
        } catch (Exception ex) {
            int failures = consecutiveFailures.incrementAndGet();
            boolean down = failures >= failureThreshold;
            return down
                    ? Health.outOfService()
                    .withDetail("consecutiveFailures", failures)
                    .withDetail("error", ex.getMessage())
                    .build()
                    : Health.up()
                    .withDetail("degraded", true)
                    .withDetail("consecutiveFailures", failures)
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
