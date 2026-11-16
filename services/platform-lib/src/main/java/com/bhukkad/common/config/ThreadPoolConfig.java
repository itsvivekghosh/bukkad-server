package com.bhukkad.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Declares the named bounded-elastic schedulers from §4.6 (thread-pool tuning):
 * <ul>
 *   <li>{@code jdbcScheduler} — core=32, queue=1000 (blocking JDBC access off
 *       the event loop)</li>
 *   <li>{@code redisScheduler} — core=16, queue=500 (blocking Redis ops off the
 *       event loop)</li>
 *   <li>{@code externalApiScheduler} — core=64, queue=2000 (outbound HTTP /
 *       WebClient calls)</li>
 * </ul>
 *
 * <p>Each scheduler is backed by a {@link MeteredThreadPoolExecutor} (same bean
 * name with {@code Executor} suffix) so platform-lib's {@link
 * com.bhukkad.common.metrics.ThreadPoolMetrics} can bind {@code pool_active},
 * {@code pool_queued} and {@code pool_rejected} gauges to it. The executor is
 * wrapped with {@link Schedulers#fromExecutorService} and exposed as a Reactor
 * {@link Scheduler} for injection into WebFlux services.
 *
 * <p>Pools are bounded (core == max threads, then a bounded queue, then
 * rejection) so saturation fails fast instead of growing unbounded. Core/queue
 * sizes are documented in gateway/identity {@code application.yml} and should
 * only be raised when the matching gauge shows persistent saturation.
 */
@Configuration
public class ThreadPoolConfig {

    @Bean("jdbcSchedulerExecutor")
    public MeteredThreadPoolExecutor jdbcSchedulerExecutor() {
        return boundedExecutor(32, 1000, "bhukkad-jdbc-scheduler");
    }

    @Bean("jdbcScheduler")
    public Scheduler jdbcScheduler(
            @Qualifier("jdbcSchedulerExecutor") MeteredThreadPoolExecutor executor) {
        return Schedulers.fromExecutorService(executor, "jdbc-scheduler");
    }

    @Bean("redisSchedulerExecutor")
    public MeteredThreadPoolExecutor redisSchedulerExecutor() {
        return boundedExecutor(16, 500, "bhukkad-redis-scheduler");
    }

    @Bean("redisScheduler")
    public Scheduler redisScheduler(
            @Qualifier("redisSchedulerExecutor") MeteredThreadPoolExecutor executor) {
        return Schedulers.fromExecutorService(executor, "redis-scheduler");
    }

    @Bean("externalApiSchedulerExecutor")
    public MeteredThreadPoolExecutor externalApiSchedulerExecutor() {
        return boundedExecutor(64, 2000, "bhukkad-external-api-scheduler");
    }

    @Bean("externalApiScheduler")
    public Scheduler externalApiScheduler(
            @Qualifier("externalApiSchedulerExecutor") MeteredThreadPoolExecutor executor) {
        return Schedulers.fromExecutorService(executor, "external-api-scheduler");
    }

    private MeteredThreadPoolExecutor boundedExecutor(int corePoolSize, int queueCapacity,
                                                      String threadName) {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };
        return new MeteredThreadPoolExecutor(corePoolSize, corePoolSize, 0L,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>(queueCapacity), threadFactory);
    }
}
