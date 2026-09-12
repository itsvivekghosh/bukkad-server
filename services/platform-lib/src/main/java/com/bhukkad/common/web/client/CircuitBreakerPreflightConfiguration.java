package com.bhukkad.common.web.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Registers the {@link CircuitBreakerPreflight} boot self-check for
 * {@code prod} deployments (P1, audit PERF-1/V-16 / docs §VI.4.2).
 *
 * <p>Guarded on WebClient being present: outside the reactive-client world
 * there are no outbound platform breakers to verify, and the platform
 * profile must stay loadable in plain-servlet contexts. The preflight itself
 * additionally no-ops when no {@link CircuitBreakerRegistry} bean exists —
 * see {@link CircuitBreakerPreflight}. Registered like the other
 * {@code com.bhukkad.common} infrastructure via component scan of the
 * service applications; the gateway (whose scan is limited to
 * {@code com.bhukkad.gateway}) is untouched by design.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
@EnableConfigurationProperties(CircuitBreakerPreflightProperties.class)
public class CircuitBreakerPreflightConfiguration {

    @Bean
    @Profile("prod")
    public CircuitBreakerPreflight circuitBreakerPreflight(
            ObjectProvider<CircuitBreakerRegistry> registryProvider,
            CircuitBreakerPreflightProperties properties) {
        return new CircuitBreakerPreflight(registryProvider, properties);
    }
}
