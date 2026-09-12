package com.bhukkad.payment.infrastructure.messaging;

import com.bhukkad.common.error.PaymentGatewayException;
import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.service.PaymentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Async saga consumer (roadmap feature #3 pairing): consumes
 * {@code payment_requested} from the order side and charges through the PSP
 * adapter (strategy-selected {@code PaymentGateway}) with the SAME
 * {@code PAYMENT_CHARGE} idempotency contract as the synchronous path, then
 * emits {@code payment_settled} | {@code payment_failed} (payload:
 * orderId, paymentId, providerRef?, reason?) via the transactional outbox.
 *
 * <p>Contract (binding, W1): payload JSON fields {@code orderId},
 * {@code customerId}, {@code amount}, {@code currency},
 * {@code idempotencyKey}. The {@code idempotencyKey} is the charge claim key
 * (scope {@code PAYMENT_CHARGE}), so a redelivered request can never
 * double-charge; the settle/emit happens inside
 * {@link PaymentService#processPaymentRequested}, which also emits
 * {@code payment_failed} in the same transaction that marks the charge
 * FAILED. The synchronous create-order path is untouched (sibling batch owns
 * order; this module never writes order state).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestedConsumer {

    static final String TYPE_PAYMENT_REQUESTED = "payment_requested";

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @KafkaListener(id = "payment.requested",
            topics = "${app.events.external.kafka.platform-topic:payment.events.v1}",
            groupId = "${app.events.external.kafka.consumer-group:payment-platform-consumer}")
    public void onPaymentRequested(String payload) {
        // Throws on an unparsable envelope -> DefaultErrorHandler retries -> DLT.
        PlatformEventMessage event = parseEnvelope(payload);
        if (!TYPE_PAYMENT_REQUESTED.equals(event.eventType())) {
            return; // deliberate skip: not our event type
        }
        JsonNode data = readPayload(event);
        Long orderId = nonNegative(data.path("orderId").asLong(0L), "orderId", event);
        Long customerId = nonNegative(data.path("customerId").asLong(0L), "customerId", event);
        BigDecimal amount = parseAmount(data.path("amount").asText(null), event);
        String currency = data.path("currency").asText(null);
        String idempotencyKey = data.path("idempotencyKey").asText(null);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PoisonEventException(
                    "payment_requested without an idempotencyKey: eventId=" + event.eventId());
        }

        try {
            Payment settled = paymentService.processPaymentRequested(
                    orderId, customerId, amount, currency, idempotencyKey);
            log.info("PAYMENT_REQUESTED_SETTLED | orderId={} | paymentId={} | providerRef={} | eventId={}",
                    orderId, settled.getId(), settled.getProviderRef(), event.eventId());
        } catch (PaymentGatewayException declined) {
            // Terminal, expected outcome: payment_failed was already emitted by
            // the service in the failure transaction. Nothing to rethrow — a
            // redelivery would hit the FAILED claim and no-op.
            log.warn("PAYMENT_REQUESTED_DECLINED | orderId={} | eventId={} | reason={}",
                    orderId, event.eventId(), declined.getMessage());
        } catch (DataIntegrityViolationException dup) {
            // Lost a concurrent claim race for the same idempotencyKey; the
            // winner charged and emitted. Benign at-least-once duplication.
            log.info("PAYMENT_REQUESTED_CONCURRENT_DUPLICATE | orderId={} | eventId={}",
                    orderId, event.eventId());
        }
    }

    private Long nonNegative(long value, String field, PlatformEventMessage event) {
        if (value <= 0) {
            throw new PoisonEventException(
                    "payment_requested without a usable " + field + ": eventId=" + event.eventId());
        }
        return value;
    }

    private BigDecimal parseAmount(String raw, PlatformEventMessage event) {
        if (raw == null || raw.isBlank()) {
            throw new PoisonEventException(
                    "payment_requested without an amount: eventId=" + event.eventId());
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new PoisonEventException(
                    "payment_requested with a non-numeric amount '" + raw + "': eventId="
                            + event.eventId(), e);
        }
    }

    private PlatformEventMessage parseEnvelope(String payload) {
        try {
            return objectMapper.readValue(payload, PlatformEventMessage.class);
        } catch (Exception e) {
            throw new PoisonEventException("Malformed event envelope", e);
        }
    }

    private JsonNode readPayload(PlatformEventMessage event) {
        try {
            return objectMapper.readTree(event.payload());
        } catch (Exception e) {
            throw new PoisonEventException("Malformed event payload: " + event.eventId(), e);
        }
    }
}
