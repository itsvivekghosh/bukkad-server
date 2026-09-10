package com.bhukkad.restaurant.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.Restaurant;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes the SEARCH-SYNC domain events from ADR-002 via the transactional
 * outbox ({@code restaurant.events.v1}): {@code restaurant_updated},
 * {@code menu_item_changed} and {@code menu_item_deleted}. The search
 * service's {@code SearchSyncEventConsumer} maintains
 * {@code restaurant_search}/{@code menu_item_search} from these.
 *
 * <p><strong>G-1 contract (mirrors OrderEventPublisher):</strong> enqueue
 * failures PROPAGATE so the business transaction (menu mutation + event) rolls
 * back atomically. The older {@link RestaurantEventPublisher} swallowed —
 * these sync events are the authoritative index feed, so they must not.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuEventsPublisher {

    public static final String TYPE_RESTAURANT_UPDATED = "restaurant_updated";
    public static final String TYPE_MENU_ITEM_CHANGED = "menu_item_changed";
    public static final String TYPE_MENU_ITEM_DELETED = "menu_item_deleted";

    private final OutboxClient outboxClient;
    private final ObjectMapper objectMapper;

    /** {@code restaurant_updated} payload: the fields the search projection renders. */
    public void restaurantUpdated(Restaurant restaurant) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", restaurant.getId());
        payload.put("name", restaurant.getName());
        payload.put("description", restaurant.getDescription());
        payload.put("imageUrl", restaurant.getImageUrl());
        payload.put("isOpen", Boolean.TRUE.equals(restaurant.getIsOpen()));
        payload.put("isActive", Boolean.TRUE.equals(restaurant.getIsActive()));
        payload.put("averageRating", restaurant.getAvgRating());
        payload.put("totalReviews", restaurant.getTotalReviews() == null ? 0 : restaurant.getTotalReviews());
        enqueue(TYPE_RESTAURANT_UPDATED, restaurant.getId(), payload);
    }

    /** {@code menu_item_changed} payload: fields the menu-item search projection renders. */
    public void menuItemChanged(MenuItem item, String restaurantName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", item.getId());
        payload.put("restaurantId", item.getRestaurantId());
        payload.put("name", item.getName());
        payload.put("description", item.getDescription());
        payload.put("price", item.getPrice());
        payload.put("originalPrice", item.getOriginalPrice());
        payload.put("discountPercentage", item.getDiscountPercentage());
        payload.put("available", Boolean.TRUE.equals(item.getIsAvailable()));
        payload.put("foodType", item.getFoodType() == null ? null : item.getFoodType().name());
        payload.put("isVeg", Boolean.TRUE.equals(item.getIsVeg()));
        payload.put("imageUrl", item.getImageUrl());
        payload.put("preparationTime", item.getPreparationTime());
        payload.put("bestseller", Boolean.TRUE.equals(item.getBestseller()));
        payload.put("restaurantName", restaurantName);
        enqueue(TYPE_MENU_ITEM_CHANGED, item.getId(), payload);
    }

    /** {@code menu_item_deleted}: search removes the row (no orphan hits, ADR-002). */
    public void menuItemDeleted(Long menuItemId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", menuItemId);
        enqueue(TYPE_MENU_ITEM_DELETED, menuItemId, payload);
    }

    private void enqueue(String type, Long aggregateId, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            PlatformEventMessage message = PlatformEventMessage.of(type, String.valueOf(aggregateId), json);
            // No try/catch around the enqueue itself: a failed index-feed write
            // must roll the menu mutation back with it (G-1; OutboxClient
            // hard-throws outside a transaction anyway).
            outboxClient.enqueue(message, aggregateId);
            log.info("MENU_EVENT_ENQUEUED | type={} | aggregateId={}", type, aggregateId);
        } catch (java.io.IOException e) {
            // Jackson serialization failure is deterministic, not transient.
            throw new IllegalStateException("Menu event payload serialization failed", e);
        }
    }
}
