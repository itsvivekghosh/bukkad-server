package com.bhukkad.restaurant.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Publishes restaurant domain events via the transactional outbox (plan §6.2,
 * {@code restaurant.events.v1}): {@code RestaurantCreated},
 * {@code RestaurantAvailabilityChanged}, {@code MenuChanged}. The outbox row
 * commits atomically with the domain change; the poller ships it to Kafka.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantEventPublisher {

    public static final String TOPIC = "restaurant.events.v1";
    public static final String TYPE_RESTAURANT_CREATED = "RestaurantCreated";
    public static final String TYPE_AVAILABILITY_CHANGED = "RestaurantAvailabilityChanged";
    public static final String TYPE_MENU_CHANGED = "MenuChanged";

    private final OutboxClient outboxClient;
    private final ObjectMapper objectMapper;

    public void restaurantCreated(Long restaurantId, String name) {
        enqueue(TYPE_RESTAURANT_CREATED, restaurantId, "{\"id\":%d,\"name\":\"%s\"}".formatted(restaurantId, name));
    }

    public void availabilityChanged(Long restaurantId, boolean active) {
        enqueue(TYPE_AVAILABILITY_CHANGED, restaurantId, "{\"id\":%d,\"active\":%b}".formatted(restaurantId, active));
    }

    public void menuChanged(Long restaurantId, Long menuItemId) {
        enqueue(TYPE_MENU_CHANGED, restaurantId, "{\"restaurantId\":%d,\"menuItemId\":%d}".formatted(restaurantId, menuItemId));
    }

    private void enqueue(String type, Long aggregateId, String payload) {
        try {
            PlatformEventMessage message = PlatformEventMessage.of(type, String.valueOf(aggregateId), payload);
            outboxClient.enqueue(message, aggregateId);
            log.info("RESTAURANT_EVENT_ENQUEUED | type={} | restaurantId={}", type, aggregateId);
        } catch (Exception e) {
            log.error("RESTAURANT_EVENT_ENQUEUE_FAILED | type={} | restaurantId={}", type, aggregateId, e);
        }
    }
}
