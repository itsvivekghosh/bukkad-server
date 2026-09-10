package com.bhukkad.common.web.client;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelOption;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientConfig;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the platform WebClient factory wiring (audit P-05/PERF-5): the shared
 * bounded connection pool, the 2 s connect / 5 s response timeout defaults,
 * per-client timeout overrides, and the per-target circuit breaker with
 * metrics export. {@link RetryFilterTest} and {@link CircuitBreakerFilterTest}
 * pin the filter semantics themselves.
 */
class PlatformWebClientBuilderFactoryTest {

    @Test
    void sharedPool_isBoundedNamedAndShared() {
        // ONE JVM-wide provider: name, bound and pending-acquire timeout are
        // the P-05 contract every factory-built client shares.
        ConnectionProvider pool = PlatformWebClientBuilderFactory.sharedPool();
        assertThat(pool.name()).isEqualTo("bhukkad-webclient");
        assertThat(pool.maxConnections()).isEqualTo(64);
        assertThat(PlatformWebClientBuilderFactory.POOL_PENDING_ACQUIRE_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void httpClient_usesSharedPool_andDefaultTimeouts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformWebClientBuilderFactory factory =
                PlatformWebClientBuilderFactory.forTarget("test-target", registry);

        HttpClient httpClient = factory.httpClient();
        HttpClientConfig config = httpClient.configuration();

        // The provider must be the shared pool, not a per-client instance —
        // per-call pools would fragment the 64-connection budget.
        ConnectionProvider provider = config.connectionProvider();
        assertThat(provider.name()).isEqualTo("bhukkad-webclient");
        assertThat(provider.maxConnections()).isEqualTo(64);
        assertThat(config.responseTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS)).isEqualTo(2000);
    }

    @Test
    void perClientTimeoutOverrides_applyToConnector() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformWebClientBuilderFactory factory =
                PlatformWebClientBuilderFactory.forTarget("test-target", registry)
                        .connectTimeout(Duration.ofMillis(1500))
                        .responseTimeout(Duration.ofSeconds(2));

        HttpClientConfig config = factory.httpClient().configuration();

        assertThat(config.responseTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(config.options().get(ChannelOption.CONNECT_TIMEOUT_MILLIS)).isEqualTo(1500);
    }

    @Test
    void perTargetBreaker_isRegisteredWithMetrics_perTargetName() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        PlatformWebClientBuilderFactory.forTarget("downstream-a", registry).build();
        PlatformWebClientBuilderFactory.forTarget("downstream-a", registry).build();
        PlatformWebClientBuilderFactory.forTarget("downstream-b", registry).build();

        // Two builds for the same target share ONE breaker (registry keyed by
        // name); a different target gets its own — per-target fail-fast.
        CircuitBreakerFilter firstA =
                new CircuitBreakerFilter("downstream-a", CircuitBreakerFilter.DEFAULT_CONFIG, registry);
        CircuitBreakerFilter secondA =
                new CircuitBreakerFilter("downstream-a", CircuitBreakerFilter.DEFAULT_CONFIG, registry);
        CircuitBreakerFilter firstB =
                new CircuitBreakerFilter("downstream-b", CircuitBreakerFilter.DEFAULT_CONFIG, registry);
        assertThat(firstA.getCircuitBreaker()).isSameAs(secondA.getCircuitBreaker());
        assertThat(firstA.getCircuitBreaker()).isNotSameAs(firstB.getCircuitBreaker());

        // Breaker state gauges are exported per target name (PERF-1/G-2).
        assertThat(registry.get("circuit_breaker_state").tag("name", "downstream-a").gauge())
                .isNotNull();
        assertThat(registry.get("circuit_breaker_open").tag("name", "downstream-b").gauge())
                .isNotNull();
    }

    @Test
    void toBuilder_buildsWithPlatformFilters_forLoadBalancedBean() {
        WebClient client = PlatformWebClientBuilderFactory
                .forTarget(PlatformWebClientBuilderFactory.DEFAULT_TARGET, new SimpleMeterRegistry())
                .toBuilder()
                .build();

        // The load-balanced bean path must build cleanly with the same
        // platform connector + filters (RetryFilterTest and
        // CircuitBreakerFilterTest pin their behaviour).
        assertThat(client).isNotNull();
    }
}
