package com.bhukkad.commerce.delivery.config;

import org.springframework.context.annotation.Configuration;

/**
 * Delivery-service-local HTTP client configuration for OSRM route/distance calls.
 *
 * <p>Previously held the {@code osrmRestTemplate} {@code RestTemplate} bean.
 * {@link com.bhukkad.commerce.delivery.infrastructure.client.OsrmClient} now builds its own
 * WebClient via {@link com.bhukkad.common.web.client.PlatformWebClientBuilderFactory}
 * for the shared bounded connection pool, per-target circuit breaker, and
 * non-blocking I/O — no RestTemplate bean is required.</p>
 *
 * <p>Kept as a configuration placeholder in case future OSRM-specific beans
 * are needed.</p>
 */
@Configuration
public class OsrmHttpConfig {
}
