package com.bhukkad.order.client;

import com.bhukkad.common.web.client.CircuitBreakerFilter;
import com.bhukkad.common.web.client.RetryFilter;
import com.bhukkad.order.client.dto.RestaurantResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Service-to-service client for the Restaurant service.
 *
 * <p>Uses WebClient with built-in resilience: retry (3 attempts, 1s backoff),
 * circuit breaker (50% failure threshold, 10s open state), and timeout (3s).</p>
 */
@Component
public class RestaurantClient {

    private final WebClient webClient;

    public RestaurantClient(@Value("${app.services.restaurant.url}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .filter(new RetryFilter(3, Duration.ofSeconds(1)))
                .filter(new CircuitBreakerFilter("restaurant", CircuitBreakerFilter.DEFAULT_CONFIG))
                .build();
    }

    /**
     * Fetch a public restaurant profile by ID.
     *
     * @param id the restaurant ID
     * @return Mono of RestaurantResponse, empty if not found
     */
    public Mono<RestaurantResponse> getRestaurant(Long id) {
        return webClient.get()
                .uri("/api/v1/restaurants/public/{id}", id)
                .retrieve()
                .bodyToMono(RestaurantResponse.class)
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> {
                    // Log and return empty for downstream handling
                    return Mono.empty();
                });
    }

    /**
     * Search restaurants by keyword.
     *
     * @param keyword search term
     * @return Mono of list of RestaurantResponse
     */
    public Mono<List<RestaurantResponse>> searchRestaurants(String keyword) {
        return webClient.get()
                .uri("/api/v1/restaurants/public/search?keyword={keyword}", keyword)
                .retrieve()
                .bodyToFlux(RestaurantResponse.class)
                .collectList()
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> Mono.just(List.of()));
    }
}
