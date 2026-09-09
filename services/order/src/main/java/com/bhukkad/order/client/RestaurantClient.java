package com.bhukkad.order.client;

import com.bhukkad.common.web.client.CircuitBreakerFilter;
import com.bhukkad.common.web.client.RetryFilter;
import com.bhukkad.order.client.dto.MenuItemDto;
import com.bhukkad.order.client.dto.MenuSnapshot;
import com.bhukkad.order.client.dto.RestaurantResponse;
import com.bhukkad.order.client.dto.StockReservationLine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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
     * snapshot.
     *
     * <p>Error contract: a genuine 404 from restaurant resolves to an EMPTY
     * Mono ("item does not exist"); every other failure — timeout, connection
     * refused, 5xx, restart churn — propagates as an error signal so callers
     * can map it to 503 (UpstreamUnavailableException) instead of falsely
     * reporting the item as missing. (Previously all errors collapsed to
     * empty, which produced lying 404s during upstream restarts.)</p>
     */
    @SuppressWarnings("unchecked")
    public Mono<java.util.Map<String, Object>> getMenuItem(Long id) {
        return webClient.get()
                .uri("/api/v1/menu/items/{id}", id)
                .retrieve()
                .bodyToMono(java.util.Map.class)
                .map(m -> (java.util.Map<String, Object>) m)
                .timeout(java.time.Duration.ofSeconds(3))
                // Genuine 404 → empty Mono (item does not exist). Reactor
                // turns the 404 status into WebClientResponseException via
                // the default status handler; catch ONLY that class here.
                .onErrorResume(WebClientResponseException.NotFound.class,
                        e -> Mono.empty());
    }

    /**
     * Batch menu-item fetch for the checkout chord (PERF-3, one S2S RTT per
     * cart): {@code GET /api/v1/menu/items?ids=1,2,3}. The restaurant service
     * caps the list at 100 ids (over cap answers 400) and omits
     * non-existent/unavailable ids from the returned list; every item map
     * carries {@code id}, {@code restaurantId}, {@code name} and {@code price}.
     *
     * <p>Error contract follows the read conventions of this client: a
     * per-call 3 s response timeout and other failures surface as an error
     * signal for callers to map to 503. Unlike {@link #getMenuItem}, a 404 is
     * NOT mapped to "item does not exist": the batch route answers 404 only
     * when the endpoint itself is gone (upstream contract break), so it
     * propagates as an upstream failure instead of lying about the items.</p>
     */
    @SuppressWarnings("unchecked")
    public Mono<List<java.util.Map<String, Object>>> getMenuItems(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Mono.just(List.of());
        }
        String joined = ids.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return webClient.get()
                .uri("/api/v1/menu/items?ids={ids}", joined)
                .retrieve()
                .bodyToMono(java.util.Map.class)
                .timeout(java.time.Duration.ofSeconds(3))
                .map(body -> {
                    Object items = ((java.util.Map<String, Object>) body).get("items");
                    return items instanceof List<?> list
                            ? (List<java.util.Map<String, Object>>) list
                            : List.<java.util.Map<String, Object>>of();
                });
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
                .onErrorResume(e -> Mono.just(List.of()));
    }

    /**
     * Resolve the owning restaurant of a menu item (item → restaurantId).
     * Empty Mono when the item does not exist (404) or the payload carries no
     * restaurantId.
     */
    public Mono<Long> getMenuItemRestaurantId(Long menuItemId) {
        return getMenuItem(menuItemId)
                .map(m -> m.get("restaurantId"))
                .map(rid -> Long.valueOf(String.valueOf(rid)));
    }

    /**
     * Reserve stock for order lines (saga RESERVE_STOCK step). Targets the
     * restaurant service's internal inventory surface
     * {@code POST /api/v1/inventory/stock-reservation/reserve} with a
     * service token on {@code X-Service-Token} (required when the restaurant
     * service enforces mesh auth; omitted when the token is not configured).
     *
     * <p>Returns the reserved lines (with server-issued reservation ids when
     * the endpoint provides them), or — while the endpoint still answers with
     * an empty body — the request lines echoed back, so callers can only read
     * success from a 2xx. 4xx/5xx and transport failures surface as an error
     * signal (the saga step fails); nothing is swallowed here.</p>
     */
    public Mono<List<StockReservationLine>> reserveStock(List<StockReservationLine> lines, String serviceToken) {
        return stockReservation("/api/v1/inventory/stock-reservation/reserve", lines, serviceToken);
    }

    /**
     * Release previously reserved stock (saga compensation of RESERVE_STOCK).
     * Same contract as {@link #reserveStock} against
     * {@code POST /api/v1/inventory/stock-reservation/release}.
     */
    public Mono<List<StockReservationLine>> releaseStock(List<StockReservationLine> lines, String serviceToken) {
        return stockReservation("/api/v1/inventory/stock-reservation/release", lines, serviceToken);
    }

    private Mono<List<StockReservationLine>> stockReservation(String path,
                                                              List<StockReservationLine> lines,
                                                              String serviceToken) {
        return webClient.post()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    if (serviceToken != null && !serviceToken.isBlank()) {
                        headers.set("X-Service-Token", serviceToken);
                    }
                })
                .bodyValue(lines)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<StockReservationLine>>() { })
                .defaultIfEmpty(lines)
                .map(returned -> returned.isEmpty() ? lines : returned)
                // Bounds the WHOLE retry chain (filters: 3 attempts × 5s each
                // + 1s backoffs) — a 5s outer timeout cut legitimate retries
                // off mid-flight under load.
                .timeout(Duration.ofSeconds(20));
    }
}
