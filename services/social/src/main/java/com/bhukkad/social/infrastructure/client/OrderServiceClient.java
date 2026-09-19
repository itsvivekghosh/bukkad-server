package com.bhukkad.social.infrastructure.client;

import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.order.api.dto.response.OrderResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Client for the order service to create orders from social posts.
 */
@Component
public class OrderServiceClient {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceClient.class);

    private final RestClient restClient;
    private final ServiceJwtAuthTokenProvider authTokenProvider;

    public OrderServiceClient(RestClient.Builder restClientBuilder,
                              @Value("${app.services.order.url:http://order:8080}") String orderServiceUrl,
                              ServiceJwtAuthTokenProvider authTokenProvider) {
        this.authTokenProvider = authTokenProvider;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        this.restClient = restClientBuilder
                .baseUrl(orderServiceUrl)
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                })
                .build();
    }

    /**
     * Create an order from a social post.
     *
     * @param request the order creation request
     * @return the created order response
     * @throws UpstreamUnavailableException if the order service is unavailable
     */
    @CircuitBreaker(name = "orderService", fallbackMethod = "fallbackCreateOrderFromPost")
    public OrderResponse createOrderFromPost(CreateOrderRequest request) {
        try {
            // RestClient doesn't have a direct postForObject, so we use exchange
            var response = restClient.post()
                    .uri("/api/v1/orders")
                    .headers(h -> h.set("X-Service-Token", authTokenProvider.serviceToken()))
                    .body(request)
                    .retrieve()
                    .toEntity(OrderResponse.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            // Re-raise client errors (4xx) as they may be business logic errors
            throw e;
        } catch (RestClientException e) {
            log.error("Failed to create order from post: {}", e.getMessage());
            throw new UpstreamUnavailableException("order", e);
        }
    }

    /**
     * Fallback method for order service calls when circuit breaker is open.
     *
     * @param request the order creation request
     * @param throwable the exception that caused the fallback
     * @return a fallback response indicating service unavailability
     */
    public OrderResponse fallbackCreateOrderFromPost(CreateOrderRequest request, Throwable throwable) {
        log.warn("Circuit breaker open for order service, using fallback. Cause: {}", throwable.toString());
        // Return a fallback response indicating the order service is temporarily unavailable
        // In a real implementation, you might want to return a meaningful error or cached data
        throw new UpstreamUnavailableException("order", throwable);
    }
}