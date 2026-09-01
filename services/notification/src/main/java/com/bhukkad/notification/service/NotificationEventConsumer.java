package com.bhukkad.notification.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes platform events (P6 wiring) and dispatches outbound notifications.
 *
 * <p>Listens on the {@code order.events.v1} topic produced by the order
 * service. On {@code OrderCreated} a confirmation notification is dispatched
 * to the customer. The consumer is registered only when Kafka is enabled
 * ({@code app.events.external.enabled=true}) via platform-lib's
 * {@code KafkaPlatformConfig} — otherwise this bean is inert (never created),
 * so the service still boots with {@code type: log}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private static final String TOPIC_ORDER_EVENTS = "order.events.v1";
    private static final String TYPE_ORDER_CREATED = "OrderCreated";

    private final NotificationDispatchService dispatchService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = TOPIC_ORDER_EVENTS, groupId = "${app.events.external.kafka.consumer-group}")
    public void onOrderEvent(String payload) {
        try {
            PlatformEventMessage event = objectMapper.readValue(payload, PlatformEventMessage.class);
            if (TYPE_ORDER_CREATED.equals(event.eventType())) {
                String orderId = event.aggregateId();
                // The payload embeds the customer id; parse it defensively.
                Long customerId = extractCustomerId(event.payload());
                dispatchService.dispatch(
                        "email", "customer-" + customerId,
                        "order-confirmation",
                        "Order " + orderId + " confirmed",
                        "Your order " + orderId + " is confirmed. Thank you!");
                log.info("NOTIFICATION_DISPATCHED | orderId={} | customerId={}", orderId, customerId);
            }
        } catch (Exception ex) {
            log.error("NOTIFICATION_CONSUME_FAILED | error={}", ex.getMessage(), ex);
        }
    }

    private Long extractCustomerId(String payload) {
        try {
            var node = objectMapper.readTree(payload);
            return node.has("customerId") ? node.get("customerId").asLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}