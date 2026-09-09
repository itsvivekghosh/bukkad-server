package com.bhukkad.common.web.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the breaker actually DECORATES the exchange (audit V-16 — the old
 * filter only read state in onErrorResume, so it could never open):
 * failures recorded → OPEN → the next call answers a fast 503 with
 * {@code X-Circuit: open} WITHOUT touching the upstream ExchangeFunction,
 * and after waitDurationInOpenState a single HALF_OPEN probe success closes
 * the circuit again.
 */
class CircuitBreakerFilterTest {

    /** Test scale: 4-call window, open on any 2 failures, 200 ms in OPEN. */
    private static final CircuitBreakerConfig FAST_CONFIG = CircuitBreakerConfig.custom()
            .slidingWindowSize(4)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofMillis(200))
            .permittedNumberOfCallsInHalfOpenState(1)
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .build();

    private static ClientRequest getRequest() {
        return ClientRequest.create(HttpMethod.GET, URI.create("http://upstream/x")).build();
    }

    /** Each test uses its own registry+name so breakers are isolated. */
    private static CircuitBreakerFilter filterFor(String name, SimpleMeterRegistry meters) {
        return new CircuitBreakerFilter(name, FAST_CONFIG, meters,
                CircuitBreakerRegistry.of(FAST_CONFIG));
    }

    @Test
    void repeatedFailures_openTheBreaker_andNextCallIsFast503WithoutExchange() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction alwaysFailing = request -> {
            calls.incrementAndGet();
            return Mono.error(new RuntimeException("upstream down"));
        };
        CircuitBreakerFilter filter = filterFor("open-" + System.nanoTime(), new SimpleMeterRegistry());
        CircuitBreaker breaker = filter.getCircuitBreaker();

        // Drive failures: minimumNumberOfCalls=2 at 100% failure rate ⇒ OPEN.
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(filter.filter(getRequest(), alwaysFailing))
                    .expectError()
                    .verify(Duration.ofSeconds(5));
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);

        // OPEN ⇒ fail fast: 503 + X-Circuit: open, and the exchange is
        // NEVER invoked (this is the proof the operator is mounted).
        int callsWhenOpen = calls.get();
        StepVerifier.create(filter.filter(getRequest(), alwaysFailing))
                .assertNext(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(response.headers().header("X-Circuit")).containsExactly("open");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertThat(calls.get()).isEqualTo(callsWhenOpen);
    }

    @Test
    void halfOpenProbe_successClosesTheBreaker() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction failing = request -> {
            calls.incrementAndGet();
            return Mono.error(new RuntimeException("upstream down"));
        };
        CircuitBreakerFilter filter = filterFor("halfopen-" + System.nanoTime(), new SimpleMeterRegistry());
        CircuitBreaker breaker = filter.getCircuitBreaker();

        for (int i = 0; i < 2; i++) {
            filter.filter(getRequest(), failing).onErrorResume(e -> Mono.empty()).block(Duration.ofSeconds(5));
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Let the open-wait elapse (200 ms test config; no auto transition).
        Thread.sleep(300);

        // HALF_OPEN: exactly one permitted probe. A success closes the circuit.
        ExchangeFunction healthy = request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        StepVerifier.create(filter.filter(getRequest(), healthy))
                .assertNext(response -> assertThat(response.statusCode().value()).isEqualTo(200))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // And the traffic flows again: the exchange IS called on closed.
        int before = calls.get();
        filter.filter(getRequest(), healthy).block(Duration.ofSeconds(5));
        assertThat(calls.get()).isGreaterThan(before);
    }

    @Test
    void timeout_isRecordedAsBreakerFailure() {
        ExchangeFunction hanging = request -> Mono.never();
        CircuitBreakerFilter filter = filterFor("timeout-" + System.nanoTime(), new SimpleMeterRegistry());

        // The filter's inner timeout fires BEFORE the decoration completes,
        // so the breaker records the call as a failure (timeout counts).
        StepVerifier.create(filter.filter(getRequest(), hanging))
                .expectError(java.util.concurrent.TimeoutException.class)
                .verify(Duration.ofSeconds(10));
        assertThat(filter.getCircuitBreaker().getMetrics().getNumberOfFailedCalls())
                .isEqualTo(1);
    }

    @Test
    void exportedGauges_trackBreakerState() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreakerFilter filter = filterFor("gauges-" + System.nanoTime(), meters);

        assertThat(meters.find("circuit_breaker_open").gauge()).isNotNull();
        assertThat(meters.find("circuit_breaker_state").gauge().value()).isZero(); // CLOSED ordinal

        // Open it and the gauge flips.
        ExchangeFunction failing = request -> Mono.error(new RuntimeException("boom"));
        for (int i = 0; i < 2; i++) {
            filter.filter(getRequest(), failing).onErrorResume(e -> Mono.empty()).block(Duration.ofSeconds(5));
        }
        assertThat(filter.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(meters.find("circuit_breaker_open").gauge().value()).isEqualTo(1.0);
    }
}
