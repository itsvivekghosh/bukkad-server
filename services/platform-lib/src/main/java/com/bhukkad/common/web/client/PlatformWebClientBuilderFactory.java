package com.bhukkad.common.web.client;

import com.bhukkad.common.mtls.MtlsProperties;
import com.bhukkad.common.web.client.CircuitBreakerFilter;
import com.bhukkad.common.web.client.RetryFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.time.Duration;

/**
 * Factory for outbound {@link WebClient}s built on the platform defaults
 * (audit P-05/PERF-5): ONE bounded JVM-wide connection pool, explicit
 * timeouts, a per-target circuit breaker and metrics export.
 *
 * <p>When {@link MtlsProperties} is enabled, the underlying {@link HttpClient}
 * is wrapped with an {@link SslProvider} that presents a client certificate
 * and trusts the internal CA. When disabled (default), behavior is identical
 * to the previous release — plaintext HTTP.
 *
 * <p>Platform defaults (overridable per client via the fluent methods):
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
    public static final int POOL_MAX_CONNECTIONS = 256;

    /** How long a request may wait for a pooled connection before failing. */
    public static final Duration POOL_PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(3);

    /** Default TCP connect timeout. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** Default response (time-to-last-byte) timeout. */
    public static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    /** Target used by the load-balanced service-to-service client bean. */
    public static final String DEFAULT_TARGET = "default";

    /** Retry policy for transient failures on idempotent methods (PERF-1/B5). */
    static final int RETRY_ATTEMPTS = 2;
    static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);

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

    private static final ConnectionProvider PAYMENT_POOL = ConnectionProvider.builder("payment")
            .maxConnections(100)
            .pendingAcquireTimeout(POOL_PENDING_ACQUIRE_TIMEOUT)
            .metrics(true)
            .build();

    private static final ConnectionProvider ORDER_POOL = ConnectionProvider.builder("order")
            .maxConnections(100)
            .pendingAcquireTimeout(POOL_PENDING_ACQUIRE_TIMEOUT)
            .metrics(true)
            .build();

    private static final ConnectionProvider DELIVERY_POOL = ConnectionProvider.builder("delivery")
            .maxConnections(100)
            .pendingAcquireTimeout(POOL_PENDING_ACQUIRE_TIMEOUT)
            .metrics(true)
            .build();

    private final String target;
    private final MeterRegistry meterRegistry;
    private Duration connectTimeout = CONNECT_TIMEOUT;
    private Duration responseTimeout = RESPONSE_TIMEOUT;
    private final MtlsProperties mtlsProperties;
    private ConnectionProvider connectionProvider;

    private PlatformWebClientBuilderFactory(String target, MeterRegistry meterRegistry) {
        this(target, meterRegistry, null);
    }

    private PlatformWebClientBuilderFactory(String target, MeterRegistry meterRegistry, MtlsProperties mtlsProperties) {
        this.target = target;
        this.meterRegistry = meterRegistry;
        this.mtlsProperties = mtlsProperties;
    }

    /** Factory for a client targeting {@code target} (breakers are per target). */
    public static PlatformWebClientBuilderFactory forTarget(String target, MeterRegistry meterRegistry) {
        return new PlatformWebClientBuilderFactory(target, meterRegistry, null);
    }

    /**
     * Factory for a client targeting {@code target} with an explicit
     * {@link ConnectionProvider} (PERF-5 pool wiring from
     * {@link com.bhukkad.common.config.WebClientConfig}).
     */
    public static PlatformWebClientBuilderFactory forTarget(String target,
                                                             MeterRegistry meterRegistry,
                                                             ConnectionProvider connectionProvider) {
        PlatformWebClientBuilderFactory factory =
                new PlatformWebClientBuilderFactory(target, meterRegistry, null);
        factory.connectionProvider = connectionProvider;
        return factory;
    }

    /** Factory for a payment-api client using the dedicated payment pool. */
    public static PlatformWebClientBuilderFactory forPaymentTarget(String target, MeterRegistry meterRegistry) {
        PlatformWebClientBuilderFactory factory =
                new PlatformWebClientBuilderFactory(target, meterRegistry, null);
        factory.connectionProvider = PAYMENT_POOL;
        return factory;
    }

    /** Factory for an order-api client using the dedicated order pool. */
    public static PlatformWebClientBuilderFactory forOrderTarget(String target, MeterRegistry meterRegistry) {
        PlatformWebClientBuilderFactory factory =
                new PlatformWebClientBuilderFactory(target, meterRegistry, null);
        factory.connectionProvider = ORDER_POOL;
        return factory;
    }

    /** Factory for a delivery-api client using the dedicated delivery pool. */
    public static PlatformWebClientBuilderFactory forDeliveryTarget(String target, MeterRegistry meterRegistry) {
        PlatformWebClientBuilderFactory factory =
                new PlatformWebClientBuilderFactory(target, meterRegistry, null);
        factory.connectionProvider = DELIVERY_POOL;
        return factory;
    }

    /**
     * Factory for a client targeting {@code target} with optional mTLS.
     * When {@code mtlsProperties} is null or disabled, behaves identically to
     * {@link #forTarget(String, MeterRegistry)} (plaintext).
     */
    public static PlatformWebClientBuilderFactory forTarget(String target, MeterRegistry meterRegistry, MtlsProperties mtlsProperties) {
        return new PlatformWebClientBuilderFactory(target, meterRegistry, mtlsProperties);
    }

    /** The shared JVM-wide connection provider (test/diagnostics seam). */
    static ConnectionProvider sharedPool() {
        return SHARED_POOL;
    }

    /** Payment-service pool: 100 max connections, metrics-enabled (test seam). */
    static ConnectionProvider paymentPool() {
        return PAYMENT_POOL;
    }

    /** Order-service pool: 100 max connections, metrics-enabled (test seam). */
    static ConnectionProvider orderPool() {
        return ORDER_POOL;
    }

    /** Delivery-service pool: 100 max connections, metrics-enabled (test seam). */
    static ConnectionProvider deliveryPool() {
        return DELIVERY_POOL;
    }

    /**
     * The Reactor Netty {@link HttpClient} the factory builds (pool + timeout
     * defaults applied) — diagnostics seam for asserting the P-05 wiring.
     * When mTLS is enabled, the client is wrapped with an {@link SslProvider}.
     */
    HttpClient httpClient() {
        ConnectionProvider pool = connectionProvider != null ? connectionProvider : SHARED_POOL;
        HttpClient client = HttpClient.create(pool)
                .responseTimeout(responseTimeout)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis());

        if (mtlsProperties != null && mtlsProperties.isEnabled()) {
            try {
                KeyStore keyStore = KeyStore.getInstance(mtlsProperties.getKeyStoreType());
                keyStore.load(new FileInputStream(mtlsProperties.getKeyStore()),
                        mtlsProperties.getKeyStorePassword().toCharArray());
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore, mtlsProperties.getKeyStorePassword().toCharArray());

                KeyStore trustStore = KeyStore.getInstance(mtlsProperties.getTrustStoreType());
                trustStore.load(new FileInputStream(mtlsProperties.getTrustStore()),
                        mtlsProperties.getTrustStorePassword().toCharArray());
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);

                SslContext sslContext = SslContextBuilder.forClient()
                        .keyManager(kmf)
                        .trustManager(tmf)
                        .build();
                SslProvider sslProvider = SslProvider.builder()
                        .sslContext(sslContext)
                        .build();

                client = client.secure(sslProvider);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to configure mTLS for target=" + target, e);
            }
        }

        return client;
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
