package com.bhukkad.common.web.client;

import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * WebClient exchange filter that applies retry with backoff.
 *
 * <p>Retries are restricted to TRANSIENT failures (timeouts, socket/connect
 * errors, 408/5xx {@link WebClientResponseException}s) on IDEMPOTENT methods
 * only — GET/PUT/DELETE/HEAD. Never POST/PATCH (audit PERF-1/B5): retrying a
 * money-moving POST on every RuntimeException produced double-charge storms
 * that amplified outages instead of absorbing them. A generic
 * RuntimeException no longer retries — a transport failure surfaces as
 * {@link WebClientRequestException} (or with the socket exception as its
 * cause), which the transient predicate covers.</p>
 *
 * <p>Error <em>responses</em> (4xx/5xx) are successful emissions at the filter
 * level — {@code retrieve()} raises the status exception above the filters —
 * so they pass through untouched here; the predicate matters when an outer
 * path surfaces the status exception into the chain.</p>
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
        HttpMethod method = request.method();
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
                        .filter(e -> isTransient(e) && isIdempotent(method)));
    }

    /** Idempotent-by-contract methods (RFC 9110): POST/PATCH never retry. */
    public static boolean isIdempotent(HttpMethod method) {
        return HttpMethod.GET.equals(method)
                || HttpMethod.PUT.equals(method)
                || HttpMethod.DELETE.equals(method)
                || HttpMethod.HEAD.equals(method);
    }

    /** Transient failure test: timeouts/connectivity or 408/5xx statuses. */
    public static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof TimeoutException
                    || t instanceof SocketTimeoutException
                    || t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof WebClientRequestException) {
                return true;
            }
            if (t instanceof WebClientResponseException statusError) {
                int status = statusError.getStatusCode().value();
                return status == 408 || status >= 500;
            }
        }
        return false;
    }
}
