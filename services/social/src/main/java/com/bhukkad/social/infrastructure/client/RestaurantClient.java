package com.bhukkad.social.infrastructure.client;

import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Service-to-service client for the Restaurant service.
 * Uses RestClient for synchronous communication.
 */
@Component
public class RestaurantClient {

    private static final Logger log = LoggerFactory.getLogger(RestaurantClient.class);

    private final RestClient restClient;
    private final ServiceJwtAuthTokenProvider authTokenProvider;

    public RestaurantClient(RestClient.Builder restClientBuilder,
                            @Value("${app.services.restaurant.url}") String restaurantServiceUrl,
                            ServiceJwtAuthTokenProvider authTokenProvider) {
        this.authTokenProvider = authTokenProvider;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = restClientBuilder
                .baseUrl(restaurantServiceUrl)
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                })
                .build();
    }

    /**
     * Get a menu item by ID.
     * Returns empty map if item not found (404).
     *
     * @param menuItemId the menu item ID
     * @return map containing menu item details, or empty map if not found
     * @throws UpstreamUnavailableException if the restaurant service is unavailable
     */
    @CircuitBreaker(name = "restaurantService", fallbackMethod = "fallbackGetMenuItem")
    @Retry(name = "restaurantService")
    public Map<String, Object> getMenuItem(Long menuItemId) {
        try {
            return restClient.get()
                    .uri("/api/v1/menu/items/{id}", menuItemId)
                    .headers(h -> h.set("X-Service-Token", authTokenProvider.serviceToken()))
                    .retrieve()
                    .toEntity(Map.class)
                    .getBody();
        } catch (HttpClientErrorException.NotFound e) {
            // Genuine 404 -> empty map (item does not exist)
            return Map.of();
        } catch (RestClientException e) {
            log.error("Failed to get menu item {}: {}", menuItemId, e.getMessage());
            throw new UpstreamUnavailableException("restaurant", e);
        }
    }

    /**
     * Get multiple menu items by IDs.
     * Returns list of maps containing menu item details.
     *
     * @param menuItemIds the menu item IDs
     * @return list of menu item maps
     * @throws UpstreamUnavailableException if the restaurant service is unavailable
     */
    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = "restaurantService", fallbackMethod = "fallbackGetMenuItems")
    @Retry(name = "restaurantService")
    public List<Map<String, Object>> getMenuItems(List<Long> menuItemIds) {
        if (menuItemIds == null || menuItemIds.isEmpty()) {
            return List.of();
        }
        try {
            String joined = menuItemIds.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/menu/items?ids={ids}", joined)
                    .headers(h -> h.set("X-Service-Token", authTokenProvider.serviceToken()))
                    .retrieve()
                    .toEntity(Map.class)
                    .getBody();
            
            Object itemsObj = response == null ? null : response.get("items");
            if (itemsObj instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            return List.of();
        } catch (RestClientException e) {
            log.error("Failed to get menu items: {}", e.getMessage());
            throw new UpstreamUnavailableException("restaurant", e);
        }
    }

    /**
     * Get restaurant by ID.
     * Returns empty map if restaurant not found (404).
     *
     * @param restaurantId the restaurant ID
     * @return map containing restaurant details, or empty map if not found
     * @throws UpstreamUnavailableException if the restaurant service is unavailable
     */
    @CircuitBreaker(name = "restaurantService", fallbackMethod = "fallbackGetRestaurant")
    @Retry(name = "restaurantService")
    public Map<String, Object> getRestaurant(Long restaurantId) {
        try {
            return restClient.get()
                    .uri("/api/v1/restaurants/public/{id}", restaurantId)
                    .headers(h -> h.set("X-Service-Token", authTokenProvider.serviceToken()))
                    .retrieve()
                    .toEntity(Map.class)
                    .getBody();
        } catch (HttpClientErrorException.NotFound e) {
            // Genuine 404 -> empty map (restaurant does not exist)
            return Map.of();
        } catch (RestClientException e) {
            log.error("Failed to get restaurant {}: {}", restaurantId, e.getMessage());
            throw new UpstreamUnavailableException("restaurant", e);
        }
    }

    /**
     * Fallback method for restaurant service calls when circuit breaker is open.
     *
     * @param menuItemId the menu item ID
     * @param throwable the exception that caused the fallback
     * @return empty map as fallback
     */
    public Map<String, Object> fallbackGetMenuItem(Long menuItemId, Throwable throwable) {
        log.warn("Circuit breaker open for restaurant service (getMenuItem), using fallback. Cause: {}", throwable.toString());
        return Map.of(); // Return empty map as fallback (item not found)
    }

    /**
     * Fallback method for restaurant service calls when circuit breaker is open.
     *
     * @param menuItemIds the menu item IDs
     * @param throwable the exception that caused the fallback
     * @return empty list as fallback
     */
    public List<Map<String, Object>> fallbackGetMenuItems(List<Long> menuItemIds, Throwable throwable) {
        log.warn("Circuit breaker open for restaurant service (getMenuItems), using fallback. Cause: {}", throwable.toString());
        return List.of(); // Return empty list as fallback
    }

    /**
     * Fallback method for restaurant service calls when circuit breaker is open.
     *
     * @param restaurantId the restaurant ID
     * @param throwable the exception that caused the fallback
     * @return empty map as fallback
     */
    public Map<String, Object> fallbackGetRestaurant(Long restaurantId, Throwable throwable) {
        log.warn("Circuit breaker open for restaurant service (getRestaurant), using fallback. Cause: {}", throwable.toString());
        return Map.of(); // Return empty map as fallback (restaurant not found)
    }
}