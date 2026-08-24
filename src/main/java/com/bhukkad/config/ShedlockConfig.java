package com.bhukkad.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Distributed locking for {@code @Scheduled} jobs (ShedLock + Redis).
 *
 * <p>In a multi-instance deployment every instance would otherwise run every job,
 * causing duplicate outbox processing, double settlements and racing cleanups.
 * Jobs annotated with {@code @SchedulerLock} acquire a Redis lock before running;
 * only the instance that wins the lock executes the body.</p>
 *
 * <p>Locking is config-gated ({@code app.shedlock.enabled}) so single-instance
 * deployments can skip the Redis round-trip entirely. When disabled, neither the
 * lock aspect nor the provider is registered and {@code @SchedulerLock}
 * annotations are inert — jobs run unlocked exactly as before.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.shedlock", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableSchedulerLock(defaultLockAtMostFor = "${app.shedlock.lock-at-most-for:PT30M}")
public class ShedlockConfig {

    /**
     * Redis-backed lock provider. The {@code env} value namespaces all lock keys
     * so dev/staging/prod clusters sharing one Redis do not contend.
     */
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory redisConnectionFactory,
                                     @Value("${app.environment:local}") String environment) {
        return new RedisLockProvider(redisConnectionFactory, environment);
    }
}
