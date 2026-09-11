package com.bhukkad.common.web.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Prod boot self-check for the outbound circuit breakers (roadmap G-2 /
 * §VI.4.1 step 4): after the application is ready, every breaker mounted in
 * the shared {@link CircuitBreakerRegistry} is LOGGED so a flat, permanently
 * empty registry (an inert breaker fleet) is visible in operations instead
 * of surfacing only as missing {@code circuit_breaker_*} gauges.
 *
 * <p>Additionally, when the deployment declares its outbound targets via
 * {@code app.web.client.breaker-targets} (comma-separated target names, the
 * same names passed to {@link PlatformWebClientBuilderFactory#forTarget}),
 * the check ASSERTS that each configured target actually has a breaker in
 * the registry — a mis-wired client (wrong target name, filter dropped from
 * the builder) fails the boot in prod instead of silently calling upstream
 * with no fail-fast isolation.</p>
 */
@Component
@Profile("prod")
public class CircuitBreakerPreflight {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerPreflight.class);

    /** Comma-separated outbound target names that MUST have a mounted breaker. */
    static final String TARGETS_PROPERTY = "app.web.client.breaker-targets";

    private final CircuitBreakerRegistry registry;
    private final Environment environment;

    public CircuitBreakerPreflight(Environment environment) {
        this(CircuitBreakerFilter.sharedRegistry(), environment);
    }

    /** Test seam: a stub registry keeps the unit test free of global state. */
    CircuitBreakerPreflight(CircuitBreakerRegistry registry, Environment environment) {
        this.registry = registry;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyBreakersMounted() {
        List<String> mounted = registry.getAllCircuitBreakers().stream()
                .map(CircuitBreaker::getName)
                .sorted()
                .toList();
        log.info("CIRCUIT_BREAKERS_MOUNTED count={} breakers={}", mounted.size(), mounted);

        List<String> required = Arrays.stream(
                        StringUtils.commaDelimitedListToStringArray(environment.getProperty(TARGETS_PROPERTY, "")))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        List<String> missing = required.stream()
                .filter(target -> registry.find(target).isEmpty())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Outbound circuit breakers not mounted for configured targets "
                    + missing + " — every app.web.client.breaker-targets entry must be built through "
                    + "PlatformWebClientBuilderFactory so a per-target breaker exists (G-2 boot self-check)");
        }
    }
}
