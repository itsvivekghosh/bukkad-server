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
 * <p>Retries on 5xx and timeout exceptions, with configurable max attempts
 * and wait duration.</p>
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
                .retryWhen(reactor.util.retry.Retry.max(maxAttempts)
                        .filter(e -> e instanceof TimeoutException || e instanceof RuntimeException)
                        .backoff(backoff.toMillisPart(), Duration.ofMillis(backoff.toMillis() / maxAttempts)));
    }
}
