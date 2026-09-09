package com.bhukkad.common.web.client;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * WebClient exchange filter that applies retry with backoff.
 *
 * <p>Retries error signals from the exchange (timeouts, connection failures,
 * and other RuntimeExceptions) with configurable max attempts and backoff.
 * Error <em>responses</em> (4xx/5xx) are successful emissions at the filter
 * level — {@code retrieve()} raises the status exception above the filters —
 * so they pass through untouched here.</p>
 *
 * <p>Bounded: a failing upstream terminates after {@code maxAttempts} retries
 * with the configured backoff (capped at 4× backoff per wait).</p>
 */
public class RetryFilter implements ExchangeFilterFunction {

    private final int maxAttempts;
    private final Duration backoff;

    public RetryFilter(int maxAttempts, Duration backoff) {
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.defer(() -> next.exchange(request))
                .timeout(Duration.ofSeconds(5))
                // Retry.backoff(maxAttempts, minBackoff) — the fluent factory.
                // NOTE: `Retry.max(n).backoff(...)` compiles by calling the
                // STATIC Retry.backoff(long, Duration) through the instance,
                // silently discarding maxAttempts/filter and reinterpreting
                // (toMillis(), backoff) as (maxAttempts=1000, minBackoff=1s)
                // — i.e. ~17min of 1s-spaced retries instead of 3.
                .retryWhen(reactor.util.retry.Retry.backoff(maxAttempts, backoff)
                        .maxBackoff(backoff.multipliedBy(4))
                        .filter(e -> e instanceof TimeoutException || e instanceof RuntimeException));
    }
}
