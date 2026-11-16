package com.bhukkad.common.metrics;

import com.bhukkad.common.config.MeteredThreadPoolExecutor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Binds Micrometer gauges for the named schedulers declared in {@link
 * com.bhukkad.common.config.ThreadPoolConfig}:
 * <ul>
 *   <li>{@code pool_active} — threads actively executing tasks (tagged
 *       {@code scheduler=...})</li>
 *   <li>{@code pool_queued} — tasks waiting in the bounded queue</li>
 *   <li>{@code pool_rejected} — tasks rejected because pool + queue saturated</li>
 * </ul>
 *
 * <p>Every scheduler is optional: services that do not define a given scheduler
 * (e.g. a service with no JDBC access) simply skip it, so the shared component
 * never blocks a context. Gauges are tagged {@code scheduler} so Prometheus /
 * Grafana and HPA custom metrics can alert per pool.
 */
@Component
public class ThreadPoolMetrics {

    private final MeterRegistry meterRegistry;
    private final ObjectProvider<MeteredThreadPoolExecutor> jdbcScheduler;
    private final ObjectProvider<MeteredThreadPoolExecutor> redisScheduler;
    private final ObjectProvider<MeteredThreadPoolExecutor> externalApiScheduler;

    public ThreadPoolMetrics(MeterRegistry meterRegistry,
                             @Qualifier("jdbcSchedulerExecutor")
                                     ObjectProvider<MeteredThreadPoolExecutor> jdbcScheduler,
                             @Qualifier("redisSchedulerExecutor")
                                     ObjectProvider<MeteredThreadPoolExecutor> redisScheduler,
                             @Qualifier("externalApiSchedulerExecutor")
                                     ObjectProvider<MeteredThreadPoolExecutor> externalApiScheduler) {
        this.meterRegistry = meterRegistry;
        this.jdbcScheduler = jdbcScheduler;
        this.redisScheduler = redisScheduler;
        this.externalApiScheduler = externalApiScheduler;
        bind("jdbc-scheduler", jdbcScheduler.getIfAvailable());
        bind("redis-scheduler", redisScheduler.getIfAvailable());
        bind("external-api-scheduler", externalApiScheduler.getIfAvailable());
    }

    private void bind(String name, MeteredThreadPoolExecutor executor) {
        if (executor == null) {
            return;
        }
        Gauge.builder("pool_active", executor, MeteredThreadPoolExecutor::getActiveCount)
                .description("Active threads in the " + name + " scheduler")
                .tag("scheduler", name)
                .register(meterRegistry);
        Gauge.builder("pool_queued", executor, e -> e.getQueue().size())
                .description("Tasks queued in the " + name + " scheduler")
                .tag("scheduler", name)
                .register(meterRegistry);
        Gauge.builder("pool_rejected", executor, MeteredThreadPoolExecutor::getRejectedCount)
                .description("Tasks rejected by the " + name + " scheduler (saturation signal)")
                .tag("scheduler", name)
                .register(meterRegistry);
    }
}
