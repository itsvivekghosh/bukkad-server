package com.bhukkad.realtime.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.common.web.client.PlatformWebClientBuilderFactory;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Ownership oracle for customer live streams: realtime must prove the
 * subscriber's token principal owns the order before leaking its status/GPS
 * feed. That data is order-owned, so the check is a service call to
 * {@code GET /api/v1/internal/orders/{orderId}/customer} (mesh-internal,
 * {@code X-Service-Token}) — never duplicated here.
 *
 * <p>Runs on {@link PlatformWebClientBuilderFactory} (audit G-13/P-05):
 * bounded shared pool, 2 s connect + 3 s response timeout (the fail-closed
 * probe must never stall an SSE subscribe), the {@code order} circuit
 * breaker and metrics. The servlet-thread {@code block()} below is a
 * deliberate sync/async seam in an otherwise reactive stack; the call runs
 * on the MVC request thread before the emitter is handed out.</p>
 *
 * <p>FAIL-CLOSED by design: any outage, missing token or unexpected shape
 * answers {@code false}; an unavailable dependency must not open a stream, and
 * must not leak which orders exist (404 reads as "not your order").</p>
 */
@Slf4j
@Component
public class OrderOwnershipClient {

    /** Breaker/metric target name for the order service. */
    static final String TARGET = "order";
    /** Ownership probes fail fast into the deny path (was: 3 s read timeout). */
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;
    private final ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider;

    public OrderOwnershipClient(
            @Value("${app.live.order-base-url:http://order:8080}") String orderBaseUrl,
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
                log.warn("ORDER_OWNERSHIP_NO_MESH_TOKEN — service auth not configured; denying");
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
            log.warn("ORDER_OWNERSHIP_CHECK_FAILED | orderId={} | customer={} | error={}",
                    orderId, customerId, ex.getMessage());
            return false;
        }
    }
}
