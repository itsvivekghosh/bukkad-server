package com.bhukkad.common.web.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * Factory for outbound {@link WebClient}s built on the platform defaults
 * (audit P-05/PERF-5): ONE bounded JVM-wide connection pool, explicit
 * timeouts, a per-target circuit breaker and metrics export.
 *
 * <p>Platform defaults (overridable per client via the fluent methods):</p>
 * <ul>
 *   <li>connection pool: {@value #POOL_NAME}, max 64 connections,
 *       3 s pending-acquire timeout, metrics on — bounded so saturation is
 *       observable via {@code webclient_pool} gauges instead of silently
 *       growing (P-05);</li>
 *   <li>2 s connect timeout + 5 s response timeout;</li>
 *   <li>retry: transient errors only, idempotent methods only
 *       ({@link RetryFilter});</li>
 *   <li>circuit breaker: per target name from the shared registry so every
 *       client for the same downstream shares one breaker
 *       ({@link CircuitBreakerFilter}).</li>
 * </ul>
 *
 * <p>This class is the ONLY sanctioned construction point for new
 * {@code WebClient} instances outside tests — the G-13 CI guard bans
 * {@code WebClient.builder()} elsewhere. Service-to-service callers should
 * prefer the load-balanced builder bean from {@link WebClientConfig}; direct
 * external endpoints (e.g. the Twilio API) use {@link #forTarget} with their
 * own target name and custom timeouts.</p>
 */
public final class PlatformWebClientBuilderFactory {

    /** Shared Reactor Netty connection-pool name (observable in pool metrics). */
    public static final String POOL_NAME = "bhukkad-webclient";

    /** Maximum pooled connections across the JVM (PERF-5 default). */
    public static final int POOL_MAX_CONNECTIONS = 64;

    /** How long a request may wait for a pooled connection before failing. */
    public static final Duration POOL_PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(3);

    /** Default TCP connect timeout. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** Default response (time-to-last-byte) timeout. */
    public static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    /** Target used by the load-balanced service-to-service client bean. */
    public static final String DEFAULT_TARGET = "default";

    /** Retry policy for transient failures on idempotent methods (PERF-1/B5). */
    static final int RETRY_ATTEMPTS = 3;
    static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);

    /**
     * ONE JVM-wide pool shared by every factory-built client (mirrors the
     * shared breaker registry in {@link CircuitBreakerFilter}); a per-call
     * pool would fragment the 64-connection budget per client and multiply
     * metric series.
     */
    private static final ConnectionProvider SHARED_POOL = ConnectionProvider.builder(POOL_NAME)
            .maxConnections(POOL_MAX_CONNECTIONS)
            .pendingAcquireTimeout(POOL_PENDING_ACQUIRE_TIMEOUT)
            .metrics(true)
            .build();

    private final String target;
    private final MeterRegistry meterRegistry;
    private Duration connectTimeout = CONNECT_TIMEOUT;
    private Duration responseTimeout = RESPONSE_TIMEOUT;

    private PlatformWebClientBuilderFactory(String target, MeterRegistry meterRegistry) {
        this.target = target;
        this.meterRegistry = meterRegistry;
    }

    /** Factory for a client targeting {@code target} (breakers are per target). */
    public static PlatformWebClientBuilderFactory forTarget(String target, MeterRegistry meterRegistry) {
        return new PlatformWebClientBuilderFactory(target, meterRegistry);
    }

    /** The shared JVM-wide connection provider (test/diagnostics seam). */
    static ConnectionProvider sharedPool() {
        return SHARED_POOL;
    }

    /**
     * The Reactor Netty {@link HttpClient} the factory builds (pool + timeout
     * defaults applied) — diagnostics seam for asserting the P-05 wiring.
     */
    HttpClient httpClient() {
        return HttpClient.create(SHARED_POOL)
                .responseTimeout(responseTimeout)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis());
    }

    /** Overrides the platform connect timeout (default 2 s). */
    public PlatformWebClientBuilderFactory connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    /** Overrides the platform response timeout (default 5 s). */
    public PlatformWebClientBuilderFactory responseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
        return this;
    }

    /**
     * Builds the WebClient with the platform connector (shared pool +
     * timeouts), retry filter and per-target circuit breaker.
     *
     * @return fully configured WebClient
     */
    public WebClient build() {
        return toBuilder().build();
    }

    /**
     * Exposes the platform defaults on a fresh {@link WebClient.Builder} —
     * also used by the load-balanced {@code WebClientConfig} bean, whose
     * builder additionally gets Spring Cloud's LB exchange function via the
     * {@code @LoadBalanced} marker.
     *
     * @return builder pre-loaded with connector, retry and breaker
     */
    WebClient.Builder toBuilder() {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient()))
                .filter(new RetryFilter(RETRY_ATTEMPTS, RETRY_BACKOFF))
                .filter(new CircuitBreakerFilter(target, CircuitBreakerFilter.DEFAULT_CONFIG, meterRegistry));
    }
}
