package com.bhukkad.common.web.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * WebClient exchange filter that applies Resilience4j CircuitBreaker.
 *
 * <p>The breaker DECORATES the exchange via
 * {@code transformDeferred(CircuitBreakerOperator.of(breaker))} so every
 * outcome (transport error, timeout, cancellation) is RECORDED — a breaker
 * that merely reads state in an error handler can never open (audit V-16).
 * While open, calls fail fast with a 503 carrying {@code X-Circuit: open}
 * without ever touching the upstream exchange.</p>
 *
 * <p>Breakers are obtained from a {@link CircuitBreakerRegistry} keyed by
 * target name, so two clients built for the same downstream service share
 * one breaker instance (per-target fail-fast isolation).</p>
 *
 * <p>When a {@link MeterRegistry} is supplied, {@code circuit_breaker_open}
 * (1/0) and {@code circuit_breaker_state} (state ordinal) gauges tagged with
 * the breaker name are exported so the device's operation is observable —
 * an inert breaker would show a flat closed gauge forever (audit G-2).</p>
 */
public class CircuitBreakerFilter implements ExchangeFilterFunction {

    /**
     * Platform default breaker settings (audit PERF-1/V-16): window of 20
     * calls, open at 50% failures or 80% slow calls, 10 s in OPEN, 3 probes
     * allowed in HALF_OPEN.
     */
    public static final CircuitBreakerConfig DEFAULT_CONFIG = CircuitBreakerConfig.custom()
            .failureRateThreshold(50f)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)
            .minimumNumberOfCalls(10)
            .slowCallDurationThreshold(Duration.ofSeconds(2))
            .slowCallRateThreshold(80f)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

    /** Per-target breaker instances shared by all filters built with the same name. */
    private static final CircuitBreakerRegistry SHARED_REGISTRY = CircuitBreakerRegistry.ofDefaults();

    /**
     * The JVM-shared per-target registry every platform WebClient breaker
     * mounts into — exposed for the boot self-check
     * ({@link CircuitBreakerPreflight}) and diagnostics.
     */
    public static CircuitBreakerRegistry sharedRegistry() {
        return SHARED_REGISTRY;
    }


    /** Upper bound for a single upstream call; a breach is recorded as a failure. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerFilter(String name, CircuitBreakerConfig config,
                                MeterRegistry meterRegistry, CircuitBreakerRegistry breakerRegistry) {
        this.circuitBreaker = breakerRegistry.circuitBreaker(name, config);
        if (meterRegistry != null) {
            CircuitBreaker breaker = this.circuitBreaker;
            Gauge.builder("circuit_breaker_open", breaker,
                            b -> b.getState() == CircuitBreaker.State.OPEN ? 1 : 0)
                    .tag("name", name)
                    .register(meterRegistry);
            Gauge.builder("circuit_breaker_state", breaker,
                            b -> b.getState().getOrder())
                    .tag("name", name)
                    // 0 CLOSED, 1 OPEN … 6 FORCED_MANUAL_OPEN
                    .description("Resilience4j circuit breaker state ordinal for the WebClient target")
                    .register(meterRegistry);
        }
    }

    /** Named breaker with metrics export (preferred construction). */
    public CircuitBreakerFilter(String name, CircuitBreakerConfig config, MeterRegistry meterRegistry) {
        this(name, config, meterRegistry, SHARED_REGISTRY);
    }

    public CircuitBreakerFilter(String name, CircuitBreakerConfig config) {
        this(name, config, null, SHARED_REGISTRY);
    }

    public CircuitBreakerFilter(String name) {
        this(name, DEFAULT_CONFIG, null, SHARED_REGISTRY);
    }

    /** The underlying breaker — exposed for tests and health inspection. */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.defer(() -> next.exchange(request))
                .timeout(CALL_TIMEOUT)
                .flatMap(response -> {
                    if (response.statusCode().is5xxServerError()) {
                        return Mono.error(WebClientResponseException.create(
                                response.statusCode().value(),
                                 "Upstream Server Error",
                                response.headers().asHttpHeaders(),
                                null,
                                null));
                    }
                    return Mono.just(response);
                })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(CallNotPermittedException.class, e -> Mono.just(
                        ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                                .header("X-Circuit", "open")
                                .build()));
    }
}
