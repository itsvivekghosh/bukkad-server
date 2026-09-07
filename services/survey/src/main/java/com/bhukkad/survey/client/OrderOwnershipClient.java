package com.bhukkad.survey.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Proves the survey submitter owns the order being rated (audit SV-1: the
 * endpoint trusted a client-supplied orderId, letting anyone forge
 * satisfaction data for arbitrary orders/restaurants). Order-customer
 * bindings live in the order service, so this is a mesh call to the
 * internal ownership oracle with X-Service-Token.
 *
 * <p>Fail-closed: no answer or no token = not eligible.</p>
 */
@Component
public class OrderOwnershipClient {

    private static final Logger log = LoggerFactory.getLogger(OrderOwnershipClient.class);

    private final RestClient restClient;
    private final ObjectProvider<ServiceJwtAuthTokenProvider> tokenProvider;

    public OrderOwnershipClient(
            @Value("${app.survey.order-base-url:http://order:8092}") String orderBaseUrl,
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
                log.warn("SURVEY_OWNERSHIP_NO_MESH_TOKEN — denying");
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
            log.warn("SURVEY_OWNERSHIP_CHECK_FAILED | orderId={} | customer={} | error={}",
                    orderId, customerId, ex.getMessage());
            return false;
        }
    }
}
