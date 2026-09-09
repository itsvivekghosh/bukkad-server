package com.bhukkad.survey.kafka;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.survey.repository.TrendingDishRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Materializes the ANALYTICS-owned {@code trending_dishes} table from
 * ORDER_ITEMS_SNAPSHOT outbox events (published by the order domain).
 *
 * <p>Gated on {@code app.events.external.enabled} — inactive when Kafka is
 * off, in which case trending degrades to an empty list (the read path
 * already handles that). Payload shape (from the monolith's
 * OrderItemsSnapshotEvent): orderId, orderNumber, restaurantId,
 * items[{menuItemId, name, quantity}], orderedAt.</p>
 */
@Component
@ConditionalOnProperty(name = "app.events.external.enabled", havingValue = "true")
public class OrderItemsSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderItemsSnapshotConsumer.class);

    private static final String TYPE_ORDER_ITEMS_SNAPSHOT = "ORDER_ITEMS_SNAPSHOT";

    private final TrendingDishRepository trendingDishRepository;
    private final ObjectMapper objectMapper;

    public OrderItemsSnapshotConsumer(TrendingDishRepository trendingDishRepository,
                                      ObjectMapper objectMapper) {
        this.trendingDishRepository = trendingDishRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.events.external.kafka.platform-topic:bhukkad.platform.events}",
            groupId = "${app.events.external.kafka.consumer-group:survey-platform-consumer}")
    public void onPlatformEvent(String payload) {
        try {
            PlatformEventMessage event = objectMapper.readValue(payload, PlatformEventMessage.class);
            if (!TYPE_ORDER_ITEMS_SNAPSHOT.equals(event.eventType())) {
                return;
            }
            JsonNode root = objectMapper.readTree(event.payload());
            Long restaurantId = root.path("restaurantId").asLong(0L);
            String orderedAt = root.path("orderedAt").asText(null);
            LocalDateTime orderedAtTime = parse(orderedAt);
            for (JsonNode item : root.path("items")) {
                long menuItemId = item.path("menuItemId").asLong(0L);
                String name = item.path("name").asText(null);
                long quantity = item.path("quantity").asLong(1L);
                if (menuItemId <= 0 || name == null || quantity <= 0) {
                    continue;
                }
                trendingDishRepository.upsert(menuItemId, restaurantId, name, quantity, orderedAtTime);
            }
            log.info("Trending dishes updated | orderId={} | restaurantId={}",
                    event.aggregateId(), restaurantId);
        } catch (Exception ex) {
            // Degrade: a malformed event must not kill the consumer loop.
            log.warn("ORDER_ITEMS_SNAPSHOT processing failed | error={}", ex.getMessage());
        }
    }

    private LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception ignored) {
            return LocalDateTime.now();
        }
    }
}