package com.bhukkad.realtime.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.common.web.client.PlatformWebClientBuilderFactory;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

import java.time.Duration;
import java.util.Map;

/**
 * Ownership oracle for customer live streams: realtime must prove the
 * subscriber's token principal owns the order before leaking its status/GPS
 * feed. That data is order-owned, so the check is a service call to
 * {@code GET /api/v1/internal/orders/{orderId}/customer} (mesh-internal,
 * {@code X-Service-Token}) — never duplicated here.
 *
 * <p>The HTTP stack comes from the platform {@link PlatformWebClientBuilderFactory}
 * (G-13/P-05): shared bounded pool, 2 s connect + 3 s response timeouts
 * (unchanged from the hand-rolled request factory), transient-only retry on
 * the idempotent GET, and a per-target circuit breaker. The mesh base URL is
 * layered on with {@code mutate()} because the factory builds target-scoped
 * clients without one.</p>
 *
 * <p>FAIL-CLOSED by design: any outage, missing token or unexpected shape
 * answers {@code false}; an unavailable dependency must not open a stream, and
 * must not leak which orders exist (404 reads as "not your order").</p>
 */
@Slf4j
@Component
public class OrderOwnershipClient {

    /** Breaker/metric target name — one breaker for ownership probes. */
    static final String TARGET = "order-ownership";
    /** Old read timeout preserved: ownership checks must not stall stream setup. */
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;
    private final ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider;

    public OrderOwnershipClient(
            @Value("${app.live.order-base-url:http://order:8080}") String orderBaseUrl,
            ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.tokenProvider = tokenProvider;
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        this.webClient = PlatformWebClientBuilderFactory.forTarget(TARGET, meterRegistry)
                .responseTimeout(RESPONSE_TIMEOUT)
                .build()
                // The factory ships no baseUrl; mutate() preserves the platform
                // connector/filters and only pins the mesh base URL.
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
        } catch (WebClientException ex) {
            log.warn("ORDER_OWNERSHIP_CHECK_FAILED | orderId={} | customer={} | error={}",
                    orderId, customerId, ex.getMessage());
            return false;
        }
    }
}
