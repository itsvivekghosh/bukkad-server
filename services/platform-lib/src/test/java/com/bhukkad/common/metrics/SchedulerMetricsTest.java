package com.bhukkad.common.metrics;

import com.bhukkad.common.config.MeteredThreadPoolExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link ThreadPoolMetrics} binds {@code pool_active},
 * {@code pool_queued} and {@code pool_rejected} Micrometer gauges (tagged
 * {@code scheduler}) for each named scheduler, and skips schedulers that are
 * not present in a given service context.
 */
class SchedulerMetricsTest {

    private static MeteredThreadPoolExecutor newExecutor(int core, int queue, String name) {
        return new MeteredThreadPoolExecutor(core, core, 0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queue), r -> new Thread(r, name));
    }

    @Test
    void gaugesRegisterForEachPresentScheduler() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredThreadPoolExecutor jdbc = newExecutor(2, 10, "jdbc-test");
        MeteredThreadPoolExecutor redis = newExecutor(1, 5, "redis-test");
        MeteredThreadPoolExecutor external = newExecutor(3, 8, "external-test");

        ObjectProvider<MeteredThreadPoolExecutor> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbc);
        ObjectProvider<MeteredThreadPoolExecutor> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        ObjectProvider<MeteredThreadPoolExecutor> externalProvider = mock(ObjectProvider.class);
        when(externalProvider.getIfAvailable()).thenReturn(external);

        new ThreadPoolMetrics(registry, jdbcProvider, redisProvider, externalProvider);

        String[] schedulers = {"jdbc-scheduler", "redis-scheduler", "external-api-scheduler"};
        for (String scheduler : schedulers) {
            assertThat(registry.get("pool_active").tag("scheduler", scheduler).gauge())
                    .as("pool_active gauge for " + scheduler).isNotNull();
            assertThat(registry.get("pool_queued").tag("scheduler", scheduler).gauge())
                    .as("pool_queued gauge for " + scheduler).isNotNull();
            assertThat(registry.get("pool_rejected").tag("scheduler", scheduler).gauge())
                    .as("pool_rejected gauge for " + scheduler).isNotNull();
            assertThat(registry.get("pool_active").tag("scheduler", scheduler).gauge().value())
                    .isEqualTo(0.0);
            assertThat(registry.get("pool_queued").tag("scheduler", scheduler).gauge().value())
                    .isEqualTo(0.0);
            assertThat(registry.get("pool_rejected").tag("scheduler", scheduler).gauge().value())
                    .isEqualTo(0.0);
        }
    }

    @Test
    void missingSchedulersAreSkipped() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectProvider<MeteredThreadPoolExecutor> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);

        new ThreadPoolMetrics(registry, empty, empty, empty);

        assertThat(registry.find("pool_active").tag("scheduler", "jdbc-scheduler").gauges())
                .isEmpty();
        assertThat(registry.find("pool_active").tag("scheduler", "redis-scheduler").gauges())
                .isEmpty();
        assertThat(registry.find("pool_active").tag("scheduler", "external-api-scheduler").gauges())
                .isEmpty();
    }
}
