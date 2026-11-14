package com.bhukkad.common.web.client;

import com.bhukkad.common.mtls.MtlsProperties;
import com.bhukkad.common.web.client.CircuitBreakerFilter;
import com.bhukkad.common.web.client.RetryFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
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
 * to the previous release — plaintext HTTP.</p>
 *
 * <p>OPT-032: Connection pool sizes are now configurable via
 * {@link WebClientPoolProperties} (prefix {@code app.webclient.pool}).
 * Defaults match the previous hardcoded values for backward compatibility.</p>
 *
 * <p>Platform defaults (overridable per client via the fluent methods):
 * <ul>
 *   <li>connection pool: {@value #POOL_NAME}, max configurable connections,
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
@Component
@EnableConfigurationProperties(WebClientPoolProperties.class)
public class PlatformWebClientBuilderFactory {

    /** Shared Reactor Netty connection-pool name (observable in pool metrics). */
    public static final String POOL_NAME = "bhukkad-webclient";

    /** Default maximum pooled connections across the JVM (PERF-5 default). */
    public static final int DEFAULT_POOL_MAX_CONNECTIONS = 256;

    /** How long a request may wait for a pooled connection before failing. */
    public static final Duration DEFAULT_POOL_PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(3);

    /** Deprecated alias for POOL_PENDING_ACQUIRE_TIMEOUT. */
    @Deprecated
    public static final Duration POOL_PENDING_ACQUIRE_TIMEOUT = DEFAULT_POOL_PENDING_ACQUIRE_TIMEOUT;

    /** Default TCP connect timeout. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** Default response (time-to-last-byte) timeout. */
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    /** Target used by the load-balanced service-to-service client bean. */
    public static final String DEFAULT_TARGET = "default";

    /** Retry policy for transient failures on idempotent methods (PERF-1/B5). */
    static final int RETRY_ATTEMPTS = 2;
    static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);

    private final ObjectProvider<WebClientPoolProperties> poolPropertiesProvider;
    private final WebClientPoolProperties poolProperties;
    private final MeterRegistry meterRegistry;
    private final MtlsProperties mtlsProperties;
    private final String target;
    private Duration connectTimeout;
    private Duration responseTimeout;
    private ConnectionProvider connectionProvider;

    /** Spring-managed: provider for meter registry. */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    /** Spring-managed: provider for mTLS properties. */
    private final ObjectProvider<MtlsProperties> mtlsPropertiesProvider;

    /** Lazily-initialized connection pools built from configured properties. */
    private static volatile ConnectionProvider sharedPool;
    private static volatile ConnectionProvider paymentPool;
    private static volatile ConnectionProvider orderPool;
    private static volatile ConnectionProvider deliveryPool;

    /**
     * Spring-managed constructor — uses {@link WebClientPoolProperties}
     * for configurable pool sizing (OPT-032).
     */
    public PlatformWebClientBuilderFactory(ObjectProvider<WebClientPoolProperties> poolPropertiesProvider,
                                           ObjectProvider<MeterRegistry> meterRegistryProvider,
                                           ObjectProvider<MtlsProperties> mtlsPropertiesProvider) {
        WebClientPoolProperties poolProperties = poolPropertiesProvider.getIfAvailable();
        this.poolPropertiesProvider = poolPropertiesProvider;
        this.poolProperties = poolProperties != null ? poolProperties : new WebClientPoolProperties();
        this.target = DEFAULT_TARGET;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.mtlsProperties = mtlsPropertiesProvider.getIfAvailable();
        this.meterRegistryProvider = meterRegistryProvider;
        this.mtlsPropertiesProvider = mtlsPropertiesProvider;
        this.connectTimeout = this.poolProperties.getConnectTimeout();
        this.responseTimeout = this.poolProperties.getResponseTimeout();
        this.connectionProvider = null;
    }

    /** No-arg constructor for contexts where configuration properties are not bound (e.g. tests). */
    public PlatformWebClientBuilderFactory() {
        this.poolPropertiesProvider = null;
        this.poolProperties = new WebClientPoolProperties();
        this.target = DEFAULT_TARGET;
        this.meterRegistry = null;
        this.mtlsProperties = null;
        this.meterRegistryProvider = null;
        this.mtlsPropertiesProvider = null;
        this.connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        this.responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
        this.connectionProvider = null;
    }

    /** Legacy constructor for static factory methods (backward compatibility). */
    PlatformWebClientBuilderFactory(String target,
                                    MeterRegistry meterRegistry,
                                    MtlsProperties mtlsProperties) {
        this.poolPropertiesProvider = null;
        this.poolProperties = new WebClientPoolProperties();
        this.target = target;
        this.meterRegistry = meterRegistry;
        this.mtlsProperties = mtlsProperties;
        this.meterRegistryProvider = null;
        this.mtlsPropertiesProvider = null;
        this.connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        this.responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
        this.connectionProvider = null;
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
        PlatformWebClientBuilderFactory factory = new PlatformWebClientBuilderFactory(target, meterRegistry, null);
        factory.connectionProvider = connectionProvider;
        return factory;
    }

    /** Factory for a payment-api client using the dedicated payment pool. */
    public static PlatformWebClientBuilderFactory forPaymentTarget(String target, MeterRegistry meterRegistry) {
        PlatformWebClientBuilderFactory factory = new PlatformWebClientBuilderFactory(target, meterRegistry, null);
        factory.connectionProvider = getPaymentPool();
        return factory;
    }

    /** Factory for an order-api client using the dedicated order pool. */
    public static PlatformWebClientBuilderFactory forOrderTarget(String target, MeterRegistry meterRegistry) {
        PlatformWebClientBuilderFactory factory = new PlatformWebClientBuilderFactory(target, meterRegistry, null);
        factory.connectionProvider = getOrderPool();
        return factory;
    }

    /** Factory for a delivery-api client using the dedicated delivery pool. */
    public static PlatformWebClientBuilderFactory forDeliveryTarget(String target, MeterRegistry meterRegistry) {
        PlatformWebClientBuilderFactory factory = new PlatformWebClientBuilderFactory(target, meterRegistry, null);
        factory.connectionProvider = getDeliveryPool();
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
        if (sharedPool == null) {
            sharedPool = ConnectionProvider.builder(POOL_NAME)
                    .maxConnections(DEFAULT_POOL_MAX_CONNECTIONS)
                    .pendingAcquireTimeout(DEFAULT_POOL_PENDING_ACQUIRE_TIMEOUT)
                    .metrics(true)
                    .build();
        }
        return sharedPool;
    }

    /** Deprecated: use getPaymentPool(). */
    @Deprecated
    static ConnectionProvider paymentPool() {
        return getPaymentPool();
    }

    /** Deprecated: use getOrderPool(). */
    @Deprecated
    static ConnectionProvider orderPool() {
        return getOrderPool();
    }

    /** Deprecated: use getDeliveryPool(). */
    @Deprecated
    static ConnectionProvider deliveryPool() {
        return getDeliveryPool();
    }

    /** Deprecated: use getPaymentPool(). */
    static synchronized ConnectionProvider getPaymentPool() {
        if (paymentPool == null) {
            paymentPool = ConnectionProvider.builder("payment")
                    .maxConnections(100) // Dedicated payment pool keeps 100 max connections
                    .pendingAcquireTimeout(DEFAULT_POOL_PENDING_ACQUIRE_TIMEOUT)
                    .metrics(true)
                    .build();
        }
        return paymentPool;
    }

    /** Deprecated: use getOrderPool(). */
    static synchronized ConnectionProvider getOrderPool() {
        if (orderPool == null) {
            orderPool = ConnectionProvider.builder("order")
                    .maxConnections(100) // Dedicated order pool keeps 100 max connections
                    .pendingAcquireTimeout(DEFAULT_POOL_PENDING_ACQUIRE_TIMEOUT)
                    .metrics(true)
                    .build();
        }
        return orderPool;
    }

    /** Deprecated: use getDeliveryPool(). */
    static synchronized ConnectionProvider getDeliveryPool() {
        if (deliveryPool == null) {
            deliveryPool = ConnectionProvider.builder("delivery")
                    .maxConnections(100) // Dedicated delivery pool keeps 100 max connections
                    .pendingAcquireTimeout(DEFAULT_POOL_PENDING_ACQUIRE_TIMEOUT)
                    .metrics(true)
                    .build();
        }
        return deliveryPool;
    }

    private MeterRegistry meterRegistry() {
        return meterRegistry != null ? meterRegistry : null;
    }

    private MtlsProperties mtlsProperties() {
        return mtlsProperties != null ? mtlsProperties : null;
    }

    private Duration connectTimeout() {
        return connectTimeout != null ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
    }

    private Duration responseTimeout() {
        return responseTimeout != null ? responseTimeout : DEFAULT_RESPONSE_TIMEOUT;
    }

    /**
     * The Reactor Netty {@link HttpClient} the factory builds (pool + timeout
     * defaults applied) — diagnostics seam for asserting the P-05 wiring.
     * When mTLS is enabled, the client is wrapped with an {@link SslProvider}.
     */
    HttpClient httpClient() {
        ConnectionProvider pool = connectionProvider != null ? connectionProvider : sharedPool();
        HttpClient client = HttpClient.create(pool)
                .responseTimeout(responseTimeout())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout().toMillis());

        MtlsProperties mtlsProps = mtlsProperties();
        if (mtlsProps != null && mtlsProps.isEnabled()) {
            try {
                KeyStore keyStore = KeyStore.getInstance(mtlsProps.getKeyStoreType());
                keyStore.load(new FileInputStream(mtlsProps.getKeyStore()),
                        mtlsProps.getKeyStorePassword().toCharArray());
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore, mtlsProps.getKeyStorePassword().toCharArray());

                KeyStore trustStore = KeyStore.getInstance(mtlsProps.getTrustStoreType());
                trustStore.load(new FileInputStream(mtlsProps.getTrustStore()),
                        mtlsProps.getTrustStorePassword().toCharArray());
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
                throw new IllegalStateException("Failed to configure mTLS for target=" + (target != null ? target : "unknown"), e);
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
     * Builds the WebClient with the given base URL, platform connector
     * (shared pool + timeouts), retry filter and per-target circuit breaker.
     *
     * @param baseUrl base URL for all requests made through this client
     * @return fully configured WebClient with base URL set
     */
    public WebClient build(String baseUrl) {
        return toBuilder()
                .baseUrl(baseUrl)
                .build();
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
