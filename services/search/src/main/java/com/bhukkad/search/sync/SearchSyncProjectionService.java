package com.bhukkad.search.sync;

import com.bhukkad.search.entity.MenuItemSearchEntity;
import com.bhukkad.search.entity.RestaurantSearchEntity;
import com.bhukkad.search.repository.MenuItemSearchRepository;
import com.bhukkad.search.repository.RestaurantSearchRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies search projection updates shared by the {@code SearchSyncEventConsumer}
 * (ADR-002 event-driven sync) and the {@code SearchReconciliationSweep}
 * (periodic repair). All writes are idempotent upserts keyed by the source id
 * (native {@code ON CONFLICT (id) DO UPDATE}, the TrendingDishRepository
 * pattern), so a replayed event or a sweep cycle repairs rather than
 * duplicates.
 */
@Service
public class SearchSyncProjectionService {

    private final RestaurantSearchRepository restaurantSearchRepository;
    private final MenuItemSearchRepository menuItemSearchRepository;

    public SearchSyncProjectionService(RestaurantSearchRepository restaurantSearchRepository,
                                       MenuItemSearchRepository menuItemSearchRepository) {
        this.restaurantSearchRepository = restaurantSearchRepository;
        this.menuItemSearchRepository = menuItemSearchRepository;
    }

    /** restaurant_updated payload → restaurant_search row (PK = restaurant id). */
    @Transactional
    public void upsertRestaurant(long id, JsonNode data) {
        restaurantSearchRepository.upsertFromEvent(
                id,
                text(data, "name"),
                text(data, "description"),
                text(data, "imageUrl"),
                bool(data, "isOpen"),
                bool(data, "isActive"),
                optDouble(data, "averageRating"),
                optInt(data, "totalReviews"),
                text(data, "cuisineSummary"));
    }

    /** menu_item_changed payload → menu_item_search row (PK = menu-item id). */
    @Transactional
    public void upsertMenuItem(long id, JsonNode data) {
        menuItemSearchRepository.upsertFromEvent(
                id,
                optLong(data, "restaurantId"),
                text(data, "name"),
                text(data, "description"),
                optDouble(data, "price"),
                optDouble(data, "originalPrice"),
                optDouble(data, "discountPercentage"),
                bool(data, "available"),
                text(data, "foodType"),
                bool(data, "isVeg"),
                text(data, "imageUrl"),
                optInt(data, "preparationTime"),
                bool(data, "bestseller"),
                text(data, "restaurantName"));
    }

    /** menu_item_deleted payload → row removal (delete propagation, no orphans). */
    @Transactional
    public void deleteMenuItem(long id, JsonNode data) {
        menuItemSearchRepository.deleteById(id);
    }

    /**
     * Reconciliation repair from the source menu snapshot. Only the fields the
     * snapshot carries are overwritten; enrichment columns written by richer
     * events are preserved (COALESCE inside the query).
     */
    @Transactional
    public void upsertMenuItemFromSource(long id, Long restaurantId, String restaurantName,
                                         String name, String description, Double price,
                                         Boolean available) {
        menuItemSearchRepository.repairFromSource(
                id, restaurantId, name, description, price, available, restaurantName);
    }

    /** Entity fetch for tests/sweeps. */
    @Transactional(readOnly = true)
    public RestaurantSearchEntity findRestaurant(long id) {
        return restaurantSearchRepository.findById(id).orElse(null);
    }

    /** Entity fetch for tests/sweeps. */
    @Transactional(readOnly = true)
    public MenuItemSearchEntity findMenuItem(long id) {
        return menuItemSearchRepository.findById(id).orElse(null);
    }

    private static String text(JsonNode data, String field) {
        JsonNode value = data.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Boolean bool(JsonNode data, String field) {
        JsonNode value = data.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asBoolean();
    }

    private static Long optLong(JsonNode data, String field) {
        JsonNode value = data.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private static Double optDouble(JsonNode data, String field) {
        JsonNode value = data.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asDouble();
    }

    private static Integer optInt(JsonNode data, String field) {
        JsonNode value = data.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }
}
