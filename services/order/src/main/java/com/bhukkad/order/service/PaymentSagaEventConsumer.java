package com.bhukkad.order.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.order.client.RestaurantClient;
import com.bhukkad.order.client.dto.StockReservationLine;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Asynchronous order saga consumer (feature #3, {@code app.order.async-saga.enabled=true}).
 * Consumes the payment verdicts (payment.events.v1) and this service's own
 * reverse events (order.events.v1), driving the saga to completion:
 *
 * <ul>
 *   <li>{@code payment_settled} (orderId, paymentId) → order CONFIRMED +
 *       dispatch signal ({@code OrderStatusChanged → CONFIRMED} outbox event);</li>
 *   <li>{@code payment_failed} (orderId, reason) → status flip to
 *       PAYMENT_FAILED + a reverse {@code stock_release_requested} outbox
 *       event committed in the SAME transaction (G-1: compensation never
 *       performs inline broker/HTTP I/O inside the consumer tx);</li>
 *   <li>{@code stock_release_requested} (own reverse event) → performs the
 *       real restaurant HTTP release via the existing conditional release
 *       API — the compensation STEP runs asynchronously from the event.</li>
 * </ul>
 *
 * <p><strong>Idempotency:</strong> verdict handlers are status-guarded
 * (AWAITING_PAYMENT → terminal), so at-least-once re-delivery is a no-op.
 * The release step is conditional (restaurant releases unknown reservations
 * as a no-op), making replays safe.</p>
 *
 * <p><strong>No silent loss</strong> (audit V-10): malformed envelopes throw
 * {@link PoisonEventException} so the platform {@code DefaultErrorHandler}
 * retries and parks the record on {@code payment.events.v1.dlt}; unknown
 * event types and unknown order ids are deliberate skips.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.order.async-saga.enabled", havingValue = "true")
public class PaymentSagaEventConsumer {

    static final String TOPIC_PAYMENT_EVENTS = "payment.events.v1";
    /** The reverse stock-release event flows over this service's own topic. */
    static final String TOPIC_ORDER_EVENTS = "order.events.v1";
    private static final String TYPE_PAYMENT_SETTLED = "payment_settled";
    private static final String TYPE_PAYMENT_FAILED = "payment_failed";
    private static final String TYPE_STOCK_RELEASE_REQUESTED = "stock_release_requested";

    /** Outer bound for a compensation RPC (matches OrderService's saga RPC bound). */
    private static final Duration RELEASE_RPC_TIMEOUT = Duration.ofSeconds(20);

    private final OrderRepository orderRepository;
    private final OrderSagaCompensationService compensationService;
    private final RestaurantClient restaurantClient;
    private final ObjectMapper objectMapper;

    private final org.springframework.beans.factory.ObjectProvider<
            com.bhukkad.common.security.ServiceJwtAuthTokenProvider> serviceJwtTokenProvider;

    @KafkaListener(
            topics = {TOPIC_PAYMENT_EVENTS, TOPIC_ORDER_EVENTS},
            groupId = "${app.events.external.kafka.consumer-group:order-saga-consumer}")
    public void onPaymentEvent(String payload) {
        PlatformEventMessage event;
        try {
            event = objectMapper.readValue(payload, PlatformEventMessage.class);
        } catch (Exception e) {
            throw new PoisonEventException("Malformed payment saga envelope", e);
        }
        switch (event.eventType()) {
            case TYPE_PAYMENT_SETTLED -> handleSettled(event);
            case TYPE_PAYMENT_FAILED -> handleFailed(event);
            case TYPE_STOCK_RELEASE_REQUESTED -> handleStockRelease(event);
            default -> log.debug("PAYMENT_SAGA_EVENT_IGNORED | type={}", event.eventType());
        }
    }

    private void handleSettled(PlatformEventMessage event) {
        JsonNode data = readPayload(event);
        Long orderId = requireOrderId(data, event);
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("PAYMENT_SETTLED_UNKNOWN_ORDER | orderId={}", orderId);
            return; // deliberate skip: nothing to project
        }
        compensationService.confirmOnPaymentSettled(orderId, data.path("paymentId").asLong(0L));
    }

    private void handleFailed(PlatformEventMessage event) {
        JsonNode data = readPayload(event);
        Long orderId = requireOrderId(data, event);
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("PAYMENT_FAILED_UNKNOWN_ORDER | orderId={}", orderId);
            return; // deliberate skip
        }
        String reason = data.path("reason").asText("payment_failed");
        compensationService.compensatePaymentFailed(orderId, reason);
    }

    /**
     * Executes the reverse stock-release (the compensation STEP, distinct
     * from the compensation DECISION which is the status flip + outbox row).
     * The outbox row's PENDING→PUBLISHED lifecycle is owned by the standard
     * poller; a failed release throws so the platform error handler retries
     * (and finally DLTs) the reverse event.
     */
    private void handleStockRelease(PlatformEventMessage event) {
        JsonNode data = readPayload(event);
        Long orderId = requireOrderId(data, event);
        List<StockReservationLine> lines;
        try {
            lines = objectMapper.readValue(data.path("lines").toString(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, StockReservationLine.class));
        } catch (Exception e) {
            throw new PoisonEventException("Malformed stock_release_requested lines: " + event.eventId(), e);
        }
        String serviceToken = serviceToken();
        try {
            restaurantClient.releaseStock(lines, serviceToken).block(RELEASE_RPC_TIMEOUT);
            log.info("STOCK_RELEASED | orderId={}", orderId);
        } catch (Exception ex) {
            // Throw so the platform DefaultErrorHandler retries (bounded →
            // DLT). The conditional release is idempotent, so replays are safe.
            throw new IllegalStateException(
                    "Stock release failed for order " + orderId + ": " + ex.getMessage(), ex);
        }
    }

    private String serviceToken() {
        var provider = serviceJwtTokenProvider.getIfAvailable();
        return provider != null ? provider.serviceToken() : null;
    }

    private Long requireOrderId(JsonNode data, PlatformEventMessage event) {
        long orderId = data.path("orderId").asLong(0L);
        if (orderId <= 0) {
            throw new PoisonEventException(
                    event.eventType() + " without a usable orderId: eventId=" + event.eventId());
        }
        return orderId;
    }

    private JsonNode readPayload(PlatformEventMessage event) {
        try {
            return objectMapper.readTree(event.payload());
        } catch (Exception e) {
            throw new PoisonEventException("Malformed payment saga payload: " + event.eventId(), e);
        }
    }
}
