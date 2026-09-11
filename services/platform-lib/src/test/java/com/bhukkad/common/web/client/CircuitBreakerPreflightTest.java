package com.bhukkad.common.web.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G-2 boot self-check (roadmap §VI.4.1 step 4): the preflight must log the
 * mounted breaker list and — when outbound targets are declared — fail the
 * boot when a configured target has no breaker in the registry. Uses a stub
 * (in-memory) registry so the test never touches the global shared one.
 */
class CircuitBreakerPreflightTest {

    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    private final Environment environment = mock(Environment.class);

    @Test
    void allConfiguredTargetsMounted_passes() {
        registry.circuitBreaker("payment-gateway", CircuitBreakerFilter.DEFAULT_CONFIG);
        registry.circuitBreaker("twilio", CircuitBreakerFilter.DEFAULT_CONFIG);
        when(environment.getProperty(CircuitBreakerPreflight.TARGETS_PROPERTY, ""))
                .thenReturn("payment-gateway,twilio");

        assertThatCode(new CircuitBreakerPreflight(registry, environment)::verifyBreakersMounted)
                .doesNotThrowAnyException();
    }

    @Test
    void configuredTargetWithoutBreaker_failsBoot() {
        registry.circuitBreaker("payment-gateway", CircuitBreakerFilter.DEFAULT_CONFIG);
        when(environment.getProperty(CircuitBreakerPreflight.TARGETS_PROPERTY, ""))
                .thenReturn("payment-gateway,orders");

        assertThatThrownBy(new CircuitBreakerPreflight(registry, environment)::verifyBreakersMounted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders")
                .hasMessageContaining("not mounted");
    }

    @Test
    void noTargetsConfigured_onlyLogsMountedList() {
        registry.circuitBreaker("default", CircuitBreakerFilter.DEFAULT_CONFIG);
        when(environment.getProperty(CircuitBreakerPreflight.TARGETS_PROPERTY, "")).thenReturn("");

        assertThatCode(new CircuitBreakerPreflight(registry, environment)::verifyBreakersMounted)
                .doesNotThrowAnyException();
    }

    @Test
    void blankTargetEntries_ignored() {
        when(environment.getProperty(CircuitBreakerPreflight.TARGETS_PROPERTY, ""))
                .thenReturn(" , payment-gateway ,");
        registry.circuitBreaker("payment-gateway", CircuitBreakerFilter.DEFAULT_CONFIG);

        assertThatCode(new CircuitBreakerPreflight(registry, environment)::verifyBreakersMounted)
                .doesNotThrowAnyException();
    }
}
