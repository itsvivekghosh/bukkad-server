package com.bhukkad.event;

import java.time.LocalDateTime;

/**
 * Published by the payment domain when split settlement completes for an
 * order. The delivery domain consumes this to record the rider earning —
 * the payment domain never writes delivery aggregates directly (modular
 * boundary; physical extraction turns this into a Kafka message on the
 * settlement topic).
 */
public record OrderSettledEvent(
        Long orderId,
        String orderNumber,
        Long restaurantId,
        Long deliveryAgentId,
        Double tipAmount,
        LocalDateTime settledAt
) {
}
