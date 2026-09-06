package com.bhukkad.order.client;

import com.bhukkad.common.web.client.CircuitBreakerFilter;
import com.bhukkad.common.web.client.RetryFilter;
import com.bhukkad.order.client.dto.MenuItemDto;
import com.bhukkad.order.client.dto.MenuSnapshot;
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
     * Fetch a single menu item by id (canonical public surface,
     * {@code GET /api/v1/menu/items/{id}}). Used by the cart to resolve an
     * authoritative name+price server-side instead of trusting the caller's
     * snapshot. Empty Mono when the item does not exist.
     */
    @SuppressWarnings("unchecked")
    public Mono<java.util.Map<String, Object>> getMenuItem(Long id) {
        return webClient.get()
                .uri("/api/v1/menu/items/{id}", id)
                .retrieve()
                .bodyToMono(java.util.Map.class)
                .map(m -> (java.util.Map<String, Object>) m)
                .timeout(java.time.Duration.ofSeconds(3))
                .onErrorResume(e -> Mono.empty());
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
     * Fetch the full menu snapshot for a restaurant.
     *
     * @param restaurantId the restaurant ID
     * @return Mono of MenuSnapshot, empty if not found
     */
    public Mono<MenuSnapshot> getMenu(Long restaurantId) {
        return webClient.get()
                .uri("/api/v1/restaurants/{restaurantId}/menu", restaurantId)
                .retrieve()
                .bodyToMono(MenuSnapshot.class)
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Search restaurants by keyword.
     *
     * <p>Targets the restaurant service's real search surface (post-extraction):
     * {@code GET /api/v1/restaurants?name=}, which returns a bare
     * {@code RestaurantSummary[]} list.</p>
     *
     * @param keyword search term
     * @return Mono of list of RestaurantResponse
     */
    public Mono<List<RestaurantResponse>> searchRestaurants(String keyword) {
        return webClient.get()
                .uri("/api/v1/restaurants?name={keyword}", keyword)
                .retrieve()
                .bodyToFlux(RestaurantResponse.class)
                .collectList()
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(e -> Mono.just(List.of()));
    }
}
