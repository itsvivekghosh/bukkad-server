package com.bhukkad.common.audit;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxEvent;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records immutable, tamper-evident {@link AuditEvent} records (architecture-audit.md
 * Section 3.3) and ships them to Kafka asynchronously via the per-service outbox.
 *
 * <p><strong>Hash chain.</strong> {@code currentHash = SHA-256(prevHash +
 * timestamp + actorId + action + resourceType + resourceId + before + after +
 * correlationId + decision)}. Each record stores the {@code prevHash} of the
 * preceding record, so any later modification to a record breaks the chain and
 * is detectable via {@link AuditChainVerifier#verifyChain(java.util.List)}.</p>
 *
 * <p><strong>Outbox.</strong> Each record is persisted inside the caller's
 * business transaction together with an {@link OutboxEvent} row; the outbox
 * poller later publishes the row to the {@code audit.events} Kafka topic, so
 * the audit write never blocks the business transaction and survives as an
 * asynchronous, at-least-once shipment.</p>
 *
 * <p>Bean registration is gated on an {@link AuditEventRepository} bean being
 * present (i.e. a service that scans {@code com.bhukkad.common.audit} and
 * enables its JPA repositories), so modules that do not adopt the audit entity
 * (for example the admin service, which keeps its own {@code audit_events}
 * table) are unaffected.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(AuditEventRepository.class)
public class AuditEventService {

    /** Event type stamped on audit outbox rows; routed to the {@code audit.events} topic by the relay. */
    public static final String AUDIT_EVENT_TYPE = "AUDIT_EVENT";
    /** Kafka topic audit outbox rows ship to. */
    public static final String AUDIT_EVENTS_TOPIC = "audit.events";
    /** Aggregate type stamped on audit outbox rows. */
    public static final String AUDIT_AGGREGATE_TYPE = "AUDIT";

    private final AuditEventRepository auditEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRED)
    public synchronized AuditEvent record(String actorId,
                                          AuditEventAction action,
                                          String resourceType,
                                          String resourceId,
                                          String before,
                                          String after,
                                          String correlationId,
                                          AuditDecision decision) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "G-1 violation: audit record must be written inside a business transaction — "
                            + "the audit record could outlive a rolled-back domain change");
        }
        Instant timestamp = Instant.now();
        String prevHash = loadPrevHash();
        String currentHash = hashChain(prevHash, timestamp, actorId, action, resourceType, resourceId,
                before, after, correlationId, decision);

        AuditEvent event = new AuditEvent();
        event.setId(UUID.randomUUID());
        event.setTimestamp(timestamp);
        event.setActorId(actorId);
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setBefore(before);
        event.setAfter(after);
        event.setCorrelationId(correlationId);
        event.setDecision(decision);
        event.setPrevHash(prevHash);
        event.setCurrentHash(currentHash);

        AuditEvent saved = auditEventRepository.save(event);
        enqueueOutbox(saved);
        log.debug("AUDIT_RECORDED | id={} | action={} | resourceType={} | resourceId={} | decision={}",
                saved.getId(), action, resourceType, resourceId, decision);
        return saved;
    }

    public static String hashChain(String prevHash,
                                   Instant timestamp,
                                   String actorId,
                                   AuditEventAction action,
                                   String resourceType,
                                   String resourceId,
                                   String before,
                                   String after,
                                   String correlationId,
                                   AuditDecision decision) {
        String payload = String.join("",
                nullToEmpty(prevHash),
                nullToEmpty(timestamp == null ? null : timestamp.toString()),
                nullToEmpty(actorId),
                nullToEmpty(action == null ? null : action.name()),
                nullToEmpty(resourceType),
                nullToEmpty(resourceId),
                nullToEmpty(before),
                nullToEmpty(after),
                nullToEmpty(correlationId),
                nullToEmpty(decision == null ? null : decision.name()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute audit record hash", e);
        }
    }

    private String loadPrevHash() {
        return auditEventRepository.findLatest()
                .map(AuditEvent::getCurrentHash)
                .orElse(AuditEvent.GENESIS_PREV_HASH);
    }

    private void enqueueOutbox(AuditEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", event.getId());
            payload.put("timestamp", event.getTimestamp());
            payload.put("actorId", event.getActorId());
            payload.put("action", event.getAction());
            payload.put("resourceType", event.getResourceType());
            payload.put("resourceId", event.getResourceId());
            payload.put("before", event.getBefore());
            payload.put("after", event.getAfter());
            payload.put("correlationId", event.getCorrelationId());
            payload.put("decision", event.getDecision());
            payload.put("prevHash", event.getPrevHash());
            payload.put("currentHash", event.getCurrentHash());
            payload.put("createdAt", event.getCreatedAt());
            String auditJson = objectMapper.writeValueAsString(payload);
            OutboxEvent outbox = new OutboxEvent();
            outbox.setEventType(AUDIT_EVENT_TYPE);
            outbox.setAggregateType(AUDIT_AGGREGATE_TYPE);
            outbox.setAggregateId(event.getResourceId() != null
                    ? Math.abs((long) event.getResourceId().hashCode()) : 0L);
            outbox.setPayload(PlatformEventMessage.of(AUDIT_EVENT_TYPE,
                    String.valueOf(outbox.getAggregateId()),
                    event.getCorrelationId(), auditJson).toJson());
            outbox.setStatus(OutboxEvent.OutboxStatus.PENDING);
            outbox.setPartitionId(0);
            outboxEventRepository.save(outbox);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enqueue audit outbox event", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}