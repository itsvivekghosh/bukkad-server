package com.bhukkad.common.web.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the RetryFilter retry semantics:
 *
 * <ul>
 *   <li>error signals from the exchange (timeouts, connection failures) are
 *       retried with backoff, bounded to maxAttempts;</li>
 *   <li>non-retryable error signals (checked IO errors) surface
 *       immediately;</li>
 *   <li>error responses (4xx/5xx) are NOT retried at the filter level — they
 *       are successful ClientResponse emissions; the status-exception is
 *       raised later by {@code retrieve()} ABOVE the filters.</li>
 * </ul>
 *
 * <p>The bounded-attempts assertions also pin a historical regression where
 * {@code Retry.max(n).backoff(...)} compiled by invoking the STATIC
 * {@code Retry.backoff(long, Duration)} through the instance: maxAttempts and
 * the error filter were silently discarded, and the arguments were
 * reinterpreted as (maxAttempts=1000, minBackoff=1s) — so a connection-refused
 * failure blocked for ~17 minutes of 1s-spaced retries instead of failing
 * fast after 3.</p>
 */
class RetryFilterTest {

    private static ClientRequest getRequest() {
        return ClientRequest.create(org.springframework.http.HttpMethod.GET, URI.create("http://upstream/x")).build();
    }

    @Test
    void errorResponse5xx_surfacesAsIs_withoutFilterLevelRetry() {
        // retrieve() raises the WebClientResponseException for 5xx statuses
        // ABOVE the exchange filters, so a 5xx response is a successful
        // emission here and must pass through untouched, exactly once.
        AtomicInteger attempts = new AtomicInteger();
        RetryFilter filter = new RetryFilter(3, Duration.ofMillis(50));

        ClientResponse response = filter.filter(getRequest(), request -> {
            attempts.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
        }).block(Duration.ofSeconds(5));

        assertThat(response).isNotNull();
        assertThat(response.statusCode().value()).isEqualTo(500);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void retryableError_isRetriedWithBackoff_andExhaustsBoundedAttempts() {
        // Errors with backoff must terminate quickly (4 attempts × ~50ms), not
        // spin for minutes — this is the assertion that catches the static
        // Retry.backoff(...) reinterpretation regression.
        AtomicInteger attempts = new AtomicInteger();
        ExchangeFunction stub = request -> {
            attempts.incrementAndGet();
            return Mono.error(new RuntimeException("connection refused"));
        };
        RetryFilter filter = new RetryFilter(3, Duration.ofMillis(50));

        long t0 = System.nanoTime();
        assertThatThrownBy(() -> filter.filter(getRequest(), stub).block(Duration.ofSeconds(10)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Retries exhausted")
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("connection refused");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // 1 initial attempt + 3 retries with ~50ms backoff must stay far
        // below the 10s block bound.
        assertThat(elapsedMs).isLessThan(5000);
        assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    void timeoutError_isRetried() {
        AtomicInteger attempts = new AtomicInteger();
        ExchangeFunction stub = request -> {
            if (attempts.incrementAndGet() < 2) {
                return Mono.error(new TimeoutException("attempt timeout"));
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        RetryFilter filter = new RetryFilter(3, Duration.ofMillis(50));

        ClientResponse response = filter.filter(getRequest(), stub).block(Duration.ofSeconds(5));

        assertThat(response).isNotNull();
        assertThat(response.statusCode().value()).isEqualTo(200);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void nonRetryableError_surfacesImmediatelyWithoutRetry() {
        // Checked IO errors are neither TimeoutException nor RuntimeException,
        // so they must not be retried. block() wraps them in a
        // ReactiveException with the original as the cause.
        AtomicInteger attempts = new AtomicInteger();
        ExchangeFunction stub = request -> {
            attempts.incrementAndGet();
            return Mono.error(new IOException("body read failed"));
        };
        RetryFilter filter = new RetryFilter(3, Duration.ofMillis(50));

        assertThatThrownBy(() -> filter.filter(getRequest(), stub).block(Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("body read failed");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void successOnFirstAttempt_doesNotRetry() {
        AtomicInteger attempts = new AtomicInteger();
        ExchangeFunction stub = request -> {
            attempts.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        RetryFilter filter = new RetryFilter(3, Duration.ofMillis(50));

        ClientResponse response = filter.filter(getRequest(), stub).block(Duration.ofSeconds(5));

        assertThat(response).isNotNull();
        assertThat(response.statusCode().value()).isEqualTo(200);
        assertThat(attempts.get()).isEqualTo(1);
    }
}
