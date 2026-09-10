package com.bhukkad.search.sync;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Source-of-truth reader for the ADR-002 reconciliation sweep: re-reads the
 * restaurant service's PUBLIC read surface ({@code GET /api/v1/restaurants/public}
 * and {@code GET /api/v1/restaurants/{id}/menu} — both permitAll in the
 * restaurant SecurityConfig) so the sweep never queries another service's
 * database directly (domain-truth rule).
 *
 * <p>Bounded by construction: the sweep asks for one restaurant page
 * ({@code size} ≤ 100, enforced by the source) and one menu snapshot per
 * restaurant in the batch. All failures surface as an empty result so one
 * dead page never kills a sweep cycle (the repair just retries next tick).</p>
 */
@Component
public class SearchSourceClient {

    private final RestClient restClient;

    public SearchSourceClient(@Value("${app.service.restaurant-url}") String restaurantBaseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(restaurantBaseUrl)
                .build();
    }

    /** One page of active restaurants from the source (bounded list of ids). */
    public List<SearchSourceClient.SourceRestaurant> restaurantPage(int page, int size) {
        try {
            SourcePage body = restClient.get()
                    .uri("/api/v1/restaurants/public?page={page}&size={size}", page, size)
                    .retrieve()
                    .body(SourcePage.class);
            if (body == null || body.content() == null) {
                return List.of();
            }
            return body.content();
        } catch (RestClientException | IllegalStateException e) {
            return List.of();
        }
    }

    /** Menu snapshot of one restaurant: the items' canonical source shape. */
    public SourceMenu menu(Long restaurantId) {
        try {
            SourceMenu menu = restClient.get()
                    .uri("/api/v1/restaurants/{id}/menu", restaurantId)
                    .retrieve()
                    .body(SourceMenu.class);
            return menu == null ? null : menu;
        } catch (RestClientException | IllegalStateException e) {
            return null;
        }
    }

    /** Public restaurant profile: name/description/isActive for the restaurant row. */
    public SourceRestaurant restaurant(Long restaurantId) {
        try {
            return restClient.get()
                    .uri("/api/v1/restaurants/public/{id}", restaurantId)
                    .retrieve()
                    .body(SourceRestaurant.class);
        } catch (RestClientException | IllegalStateException e) {
            return null;
        }
    }

    // Wire shapes (subset of fields the projection needs). Public so the
    // reconciliation test can stub realistic payloads at this boundary.

    public record SourcePage(Integer page, List<SourceRestaurant> content) {
    }

    public record SourceRestaurant(Long id, String name, String description, Boolean active) {
    }

    public record SourceMenu(Long restaurantId, String restaurantName, List<SourceMenuItem> items) {

        public SourceMenu {
            items = items == null ? new ArrayList<>() : items;
        }
    }

    public record SourceMenuItem(Long id, String name, String description, Double price, Boolean available) {
    }

    /** Silence unused-import tooling: Duration documents the (future) tuning point. */
    @SuppressWarnings("unused")
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
}
