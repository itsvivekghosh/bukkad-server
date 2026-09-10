package com.bhukkad.common.web.client;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Shared WebClient configuration for service-to-service communication (P-05).
 *
 * <p>The {@link #platformWebClientBuilder(ObjectProvider)} bean is the ONLY
 * sanctioned construction point for outbound {@link WebClient}s built here:
 * it carries the platform defaults that hand-built clients historically
 * omitted — a bounded, metrics-enabled connection pool, 2 s connect / 5 s
 * response timeouts, transient-only idempotent retry, and a per-target
 * circuit breaker with exported state gauges (PERF-1/V-16).</p>
 *
 * <p>Services that need custom timeouts or their own target name build on
 * {@link PlatformWebClientBuilderFactory} instead of calling
 * {@code WebClient.builder()} — the G-13 CI guard bans new hand-built
 * clients outside this package.</p>
 *
 * <p>Conditional on WebClient being on the classpath (spring-boot-starter-webflux
 * is an optional dependency of platform-lib, so services that do not perform
 * inter-service calls skip this configuration entirely).</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
public class WebClientConfig {

    /**
     * Builder for the load-balanced, service-to-service WebClient.
     * Uses Spring Cloud LoadBalancer (no separate Eureka/Nacos needed).
     *
     * <p>Consumers MUST NOT replace the {@code clientConnector} — the pool and
     * timeouts come from the platform connector; use
     * {@link PlatformWebClientBuilderFactory} for custom targets instead.</p>
     *
     * @param meterRegistryProvider metrics registry for breaker-state gauges
     *                              (absent in contexts without actuator)
     * @return configured WebClient.Builder with platform resilience defaults
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder platformWebClientBuilder(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        PlatformWebClientBuilderFactory factory =
                PlatformWebClientBuilderFactory.forTarget(
                        PlatformWebClientBuilderFactory.DEFAULT_TARGET,
                        meterRegistryProvider.getIfAvailable());
        // baseUrl is intentionally left unset: LoadBalanced builders resolve
        // service-name hosts per request.
        return factory.toBuilder();
    }

    /**
     * Convenience bean: fully built load-balanced WebClient with the platform
     * defaults (pool, timeouts, retry, breaker, metrics).
     *
     * @param meterRegistryProvider metrics registry for breaker-state gauges
     * @return configured WebClient
     */
    @Bean
    @LoadBalanced
    public WebClient loadBalancedWebClient(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return platformWebClientBuilder(meterRegistryProvider).build();
    }
}
