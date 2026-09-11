package com.bhukkad.survey.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.common.web.client.PlatformWebClientBuilderFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Proves the survey submitter owns the order being rated (audit SV-1: the
 * endpoint trusted a client-supplied orderId, letting anyone forge
 * satisfaction data for arbitrary orders/restaurants). Order-customer
 * bindings live in the order service, so this is a mesh call to the
 * internal ownership oracle with X-Service-Token.
 *
 * <p>Runs on {@link PlatformWebClientBuilderFactory} (audit G-13/P-05):
 * bounded shared pool, 2 s connect + 3 s response timeout, the {@code order}
 * circuit breaker and metrics. Fail-closed: no answer or no token = not
 * eligible.</p>
 */
@Component
public class OrderOwnershipClient {

    private static final Logger log = LoggerFactory.getLogger(OrderOwnershipClient.class);

    /** Breaker/metric target name for the order service. */
    static final String TARGET = "order";
    /** Ownership probes fail fast into the deny path (was: 3 s read timeout). */
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;
    private final ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider;

    public OrderOwnershipClient(
            @Value("${app.survey.order-base-url:http://order:8080}") String orderBaseUrl,
            ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.tokenProvider = tokenProvider;
        this.webClient = PlatformWebClientBuilderFactory
                .forTarget(TARGET, meterRegistryProvider.getIfAvailable())
                .responseTimeout(RESPONSE_TIMEOUT)
                .build()
                // Spring 6.1: mutate() copies the platform connector + filters;
                // only the mesh base URL is added.
                .mutate()
                .baseUrl(orderBaseUrl)
                .build();
    }

    public boolean ownsOrder(Long customerId, Long orderId) {
        if (customerId == null || orderId == null) {
            return false;
        }
        try {
            ServiceJwtAuthTokenProvider provider = tokenProvider.getIfAvailable();
            if (provider == null) {
                log.warn("SURVEY_OWNERSHIP_NO_MESH_TOKEN — denying");
                return false;
            }
            Map<?, ?> response = webClient.get()
                    .uri("/api/v1/internal/orders/{orderId}/customer", orderId)
                    .header("X-Service-Token", provider.serviceToken())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            Object owner = response == null ? null : response.get("customerId");
            return owner instanceof Number n && n.longValue() == customerId;
        } catch (RuntimeException ex) {
            log.warn("SURVEY_OWNERSHIP_CHECK_FAILED | orderId={} | customer={} | error={}",
                    orderId, customerId, ex.getMessage());
            return false;
        }
    }
}
