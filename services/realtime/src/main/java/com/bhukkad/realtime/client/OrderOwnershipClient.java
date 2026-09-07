package com.bhukkad.realtime.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Ownership oracle for customer live streams: realtime must prove the
 * subscriber's token principal owns the order before leaking its status/GPS
 * feed. That data is order-owned, so the check is a service call to
 * {@code GET /api/v1/internal/orders/{orderId}/customer} (mesh-internal,
 * {@code X-Service-Token}) — never duplicated here.
 *
 * <p>FAIL-CLOSED by design: any outage, missing token or unexpected shape
 * answers {@code false}; an unavailable dependency must not open a stream, and
 * must not leak which orders exist (404 reads as "not your order").</p>
 */
@Slf4j
@Component
public class OrderOwnershipClient {

    private final RestClient restClient;
    private final ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider;

    public OrderOwnershipClient(
            @Value("${app.live.order-base-url:http://order:8092}") String orderBaseUrl,
            ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider) {
        this.tokenProvider = tokenProvider;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder()
                .baseUrl(orderBaseUrl)
                .requestFactory(factory)
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
            var response = restClient.get()
                    .uri("/api/v1/internal/orders/{orderId}/customer", orderId)
                    .header("X-Service-Token", provider.serviceToken())
                    .retrieve()
                    .body(java.util.Map.class);
            Object owner = response == null ? null : response.get("customerId");
            return owner instanceof Number n && n.longValue() == customerId;
        } catch (RestClientException ex) {
            log.warn("ORDER_OWNERSHIP_CHECK_FAILED | orderId={} | customer={} | error={}",
                    orderId, customerId, ex.getMessage());
            return false;
        }
    }
}
