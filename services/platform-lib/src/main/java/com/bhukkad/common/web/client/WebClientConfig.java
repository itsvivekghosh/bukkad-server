package com.bhukkad.common.web.client;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Shared WebClient configuration for service-to-service communication.
 *
 * <p>Provides a load-balanced WebClient bean with built-in resilience:
 * retry (3 attempts, 1s backoff — transient errors on idempotent methods
 * only), circuit breaker (20-call window, 50% failure or 80% slow-call
 * threshold, 10 s open state) with exported breaker-state gauges, and
 * timeout (3 s).
 *
 * <p>Services that need to call other services should inject this bean
 * or create a typed client wrapper around it.</p>
 *
 * <p>Conditional on WebClient being on the classpath (spring-boot-starter-webflux
 * is an optional dependency of platform-lib, so services that do not perform
 * inter-service calls skip this configuration entirely).</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
public class WebClientConfig {

    /**
     * Load-balanced WebClient for service-to-service calls.
     * Uses Spring Cloud LoadBalancer (no separate Eureka/Nacos needed).
     *
     * @param meterRegistryProvider metrics registry for breaker-state gauges
     *                              (absent in contexts without actuator)
     * @return configured WebClient with resilience filters
     */
    @Bean
    @LoadBalanced
    public WebClient loadBalancedWebClient(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return WebClient.builder()
                .filter(new RetryFilter(3, Duration.ofSeconds(1)))
                .filter(new CircuitBreakerFilter("default",
                        CircuitBreakerFilter.DEFAULT_CONFIG, meterRegistryProvider.getIfAvailable()))
                .build();
    }
}
