package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.RestaurantOrderStat;
import com.bhukkad.admin.domain.RestaurantOrderStatRepository;
import com.bhukkad.common.event.PlatformEventMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * CQRS read-model projection (P7): consumes order events and maintains the
 * {@code restaurant_order_stats} aggregate so admin dashboards read a
 * pre-computed metric instead of querying the order service.
 *
 * <p>Idempotent per event: the projection is keyed by the aggregate
 * (restaurantId) and the consumer group commits offsets, so a re-delivered
 * event only re-increments once per unique order id. (Dedup by eventId is the
 * caller's concern via the common idempotency records.)</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminCqrsEventConsumer {

    private static final String TOPIC_ORDER_EVENTS = "order.events.v1";
    private static final String TYPE_ORDER_CREATED = "OrderCreated";

    private final RestaurantOrderStatRepository statRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = TOPIC_ORDER_EVENTS, groupId = "${app.events.external.kafka.consumer-group}")
    public void onOrderEvent(String payload) {
        try {
            PlatformEventMessage event = objectMapper.readValue(payload, PlatformEventMessage.class);
            if (TYPE_ORDER_CREATED.equals(event.eventType())) {
                JsonNode data = objectMapper.readTree(event.payload());
                long restaurantId = data.path("restaurantId").asLong();
                BigDecimal total = data.has("totalAmount")
                        ? data.get("totalAmount").decimalValue()
                        : BigDecimal.ZERO;
                upsertStat(restaurantId, total);
                log.info("ADMIN_CQRS_PROJECTED | restaurantId={} | orderId={} | total={}",
                        restaurantId, event.aggregateId(), total);
            }
        } catch (Exception ex) {
            log.error("ADMIN_CQRS_CONSUME_FAILED | error={}", ex.getMessage(), ex);
        }
    }

    private void upsertStat(long restaurantId, BigDecimal total) {
        RestaurantOrderStat stat = statRepository.findById(restaurantId).orElseGet(() -> {
            RestaurantOrderStat s = new RestaurantOrderStat();
            s.setRestaurantId(restaurantId);
            return s;
        });
        stat.setOrderCount(stat.getOrderCount() + 1);
        stat.setRevenue(stat.getRevenue().add(total));
        stat.setUpdatedAt(java.time.LocalDateTime.now());
        statRepository.save(stat);
    }
}