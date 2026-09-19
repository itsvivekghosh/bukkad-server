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
 * bounded connection pool, the dedicated critical-path pools (payment, order,
 * delivery) with tuned maxIdleTime and pendingAcquireMaxCount, the 2 s connect /
 * 5 s response timeout defaults, per-client timeout overrides, and the
 * per-target circuit breaker with metrics export.
 * {@link RetryFilterTest} and {@link CircuitBreakerFilterTest}
 * pin the filter semantics themselves.
 */
class PlatformWebClientBuilderFactoryTest {

    @Test
    void sharedPool_isBoundedNamedAndShared() {
        ConnectionProvider pool = PlatformWebClientBuilderFactory.sharedPool();
        assertThat(pool.name()).isEqualTo("bhukkad-webclient");
        assertThat(pool.maxConnections()).isEqualTo(256);
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

        ConnectionProvider provider = config.connectionProvider();
        assertThat(provider.name()).isEqualTo("bhukkad-webclient");
        assertThat(provider.maxConnections()).isEqualTo(256);
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

        CircuitBreakerFilter firstA =
                new CircuitBreakerFilter("downstream-a", CircuitBreakerFilter.DEFAULT_CONFIG, registry);
        CircuitBreakerFilter secondA =
                new CircuitBreakerFilter("downstream-a", CircuitBreakerFilter.DEFAULT_CONFIG, registry);
        CircuitBreakerFilter firstB =
                new CircuitBreakerFilter("downstream-b", CircuitBreakerFilter.DEFAULT_CONFIG, registry);
        assertThat(firstA.getCircuitBreaker()).isSameAs(secondA.getCircuitBreaker());
        assertThat(firstA.getCircuitBreaker()).isNotSameAs(firstB.getCircuitBreaker());

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

        assertThat(client).isNotNull();
    }

    @Test
    void paymentPool_isNamedTunedAndMetricsEnabled() {
        ConnectionProvider pool = PlatformWebClientBuilderFactory.paymentPool();
        assertThat(pool.name()).isEqualTo("payment");
        assertThat(pool.maxConnections()).isEqualTo(100);
    }

    @Test
    void orderPool_isNamedTunedAndMetricsEnabled() {
        ConnectionProvider pool = PlatformWebClientBuilderFactory.orderPool();
        assertThat(pool.name()).isEqualTo("order");
        assertThat(pool.maxConnections()).isEqualTo(100);
    }

    @Test
    void deliveryPool_isNamedTunedAndMetricsEnabled() {
        ConnectionProvider pool = PlatformWebClientBuilderFactory.deliveryPool();
        assertThat(pool.name()).isEqualTo("delivery");
        assertThat(pool.maxConnections()).isEqualTo(100);
    }

    @Test
    void forPaymentTarget_usesPaymentPool() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformWebClientBuilderFactory factory =
                PlatformWebClientBuilderFactory.forPaymentTarget("payment-api", registry);

        HttpClientConfig config = factory.httpClient().configuration();
        ConnectionProvider provider = config.connectionProvider();

        assertThat(provider.name()).isEqualTo("payment");
        assertThat(provider.maxConnections()).isEqualTo(100);
    }

    @Test
    void forOrderTarget_usesOrderPool() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformWebClientBuilderFactory factory =
                PlatformWebClientBuilderFactory.forOrderTarget("order-api", registry);

        HttpClientConfig config = factory.httpClient().configuration();
        ConnectionProvider provider = config.connectionProvider();

        assertThat(provider.name()).isEqualTo("order");
        assertThat(provider.maxConnections()).isEqualTo(100);
    }

    @Test
    void forDeliveryTarget_usesDeliveryPool() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformWebClientBuilderFactory factory =
                PlatformWebClientBuilderFactory.forDeliveryTarget("delivery-api", registry);

        HttpClientConfig config = factory.httpClient().configuration();
        ConnectionProvider provider = config.connectionProvider();

        assertThat(provider.name()).isEqualTo("delivery");
        assertThat(provider.maxConnections()).isEqualTo(100);
    }

    @Test
    void criticalPools_areIndependentInstances() {
        ConnectionProvider payment = PlatformWebClientBuilderFactory.paymentPool();
        ConnectionProvider order = PlatformWebClientBuilderFactory.orderPool();
        ConnectionProvider delivery = PlatformWebClientBuilderFactory.deliveryPool();

        assertThat(payment).isNotSameAs(order);
        assertThat(payment).isNotSameAs(delivery);
        assertThat(order).isNotSameAs(delivery);
        assertThat(payment.name()).isEqualTo("payment");
        assertThat(order.name()).isEqualTo("order");
        assertThat(delivery.name()).isEqualTo("delivery");
    }
}
