package com.bhukkad.common.web.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * WebClient exchange filter that applies Resilience4j CircuitBreaker.
 *
 * <p>Opens the circuit after the configured failure rate/slow call thresholds
 * are exceeded. While open, calls fail fast with a 503 response.</p>
 */
public class CircuitBreakerFilter implements ExchangeFilterFunction {

    public static final CircuitBreakerConfig DEFAULT_CONFIG = CircuitBreakerConfig.ofDefaults();

    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerFilter(String name, CircuitBreakerConfig config) {
        this.circuitBreaker = CircuitBreaker.of(name, config);
    }

    public CircuitBreakerFilter(String name) {
        this(name, DEFAULT_CONFIG);
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.defer(() -> next.exchange(request))
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    if (circuitBreaker.getState().equals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN)) {
                        return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
                    }
                    return Mono.error(e);
                });
    }
}
