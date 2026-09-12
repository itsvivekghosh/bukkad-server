package com.bhukkad.payment.infrastructure.messaging;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.payment.domain.service.PaymentService;
import com.bhukkad.payment.domain.service.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * ADR-001 dispute ownership (R-04): supportticket is the single system of
 * record for the dispute lifecycle; this consumer is payment's ONLY crediting
 * path for a resolved dispute — payment's own {@code domain/Dispute} +
 * {@code DisputeService} write path was removed with this change, so the
 * V-01-class double-credit via a second owner cannot recur.
 *
 * <p>Contract (binding, W1): topic = the platform events topic already
 * configured, {@code eventType = "dispute_resolved"}, payload JSON:
 * {@code disputeId} (long), {@code orderId} (long), {@code customerId} (long),
 * {@code refundAmount} (string decimal), {@code reason} (string).</p>
 *
 * <p><strong>Exactly-once credit:</strong> the (scope {@code DISPUTE_CREDIT},
 * key {@code disputeId}) idempotency claim and the wallet credit commit in ONE
 * transaction (AdminCqrsEventConsumer listener + idempotency-claim style) — a
 * replayed {@code dispute_resolved} cannot double-credit, and a failed credit
 * rolls the claim back so the redelivery retries cleanly.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisputeResolvedConsumer {

    static final String TYPE_DISPUTE_RESOLVED = "dispute_resolved";
    static final String SCOPE_DISPUTE_CREDIT = "DISPUTE_CREDIT";
    private static final Duration CREDIT_DEDUPE_TTL = Duration.ofDays(7);

    private final WalletService walletService;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final ObjectMapper objectMapper;

    @KafkaListener(id = "payment.dispute-resolved",
            topics = "${app.events.external.kafka.platform-topic:payment.events.v1}",
            groupId = "${app.events.external.kafka.consumer-group:payment-platform-consumer}")
    @Transactional
    public void onDisputeResolved(String payload) {
        // Throws on an unparsable envelope -> DefaultErrorHandler retries -> DLT.
        PlatformEventMessage event = parseEnvelope(payload);
        if (!TYPE_DISPUTE_RESOLVED.equals(event.eventType())) {
            return; // deliberate skip: not our event type
        }
        JsonNode data = readPayload(event);
        long disputeId = data.path("disputeId").asLong(0L);
        long orderId = data.path("orderId").asLong(0L);
        long customerId = data.path("customerId").asLong(0L);
        BigDecimal refundAmount = parseAmount(data.path("refundAmount").asText(null));
        // Validate BEFORE burning the dedupe row: a poison record parked on the
        // DLT must stay replayable once fixed (V-10 pairing).
        if (disputeId <= 0 || customerId <= 0) {
            throw new PoisonEventException(
                    "dispute_resolved without a usable disputeId/customerId: eventId=" + event.eventId());
        }
        if (refundAmount == null || refundAmount.signum() <= 0) {
            throw new PoisonEventException(
                    "dispute_resolved without a positive refundAmount: eventId=" + event.eventId());
        }

        // Exactly-once credit claim: (DISPUTE_CREDIT, disputeId). 0 = already
        // credited (or concurrently credited by another delivery).
        int claimed = idempotencyRecords.insertIfAbsent(
                String.valueOf(disputeId), SCOPE_DISPUTE_CREDIT, customerId,
                IdempotencyRecord.IdempotencyStatus.COMPLETED.name(), null,
                LocalDateTime.now().plus(CREDIT_DEDUPE_TTL));
        if (claimed == 0) {
            log.info("DISPUTE_CREDIT_DUPLICATE_SKIPPED | disputeId={} | eventId={}",
                    disputeId, event.eventId());
            return;
        }
        walletService.credit(customerId, refundAmount, "DISPUTE-" + disputeId);
        log.info("DISPUTE_CREDITED | disputeId={} | orderId={} | customerId={} | amount={} | reason={} | eventId={}",
                disputeId, orderId, customerId, refundAmount,
                data.path("reason").asText(""), event.eventId());
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
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
