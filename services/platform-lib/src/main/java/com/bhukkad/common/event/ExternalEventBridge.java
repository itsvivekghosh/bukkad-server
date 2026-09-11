package com.bhukkad.common.event;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.common.outbox.OutboxEvent;
import com.bhukkad.common.config.ExternalEventsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Bridges the per-service outbox to external event backends. When Kafka is
 * enabled and a {@link KafkaPlatformEventPublisher} is on the classpath,
 * the bridge wraps each {@link OutboxEvent} in a {@link PlatformEventMessage}
 * envelope and forwards it; otherwise it just logs.
 *
 * <p>{@link #forwardForResult(OutboxEvent)} blocks until the broker acks the
 * record (or until the configured send-timeout elapses) so the outbox
 * poller can flip the row to PUBLISHED only on a confirmed receipt. In
 * the non-Kafka path it falls back to the fire-and-forget
 * {@link #forward(OutboxEvent)} call.</p>
 *
 * @deprecated V-09 residue cleared in P3. The production event relay is the
 *             outbox poller ({@code OutboxPollPublisher →
 *             KafkaPlatformEventPublisher.publishForResult}, ack-before-flip —
 *             two-phase per PERF-2/B2); this bridge has ZERO callers in
 *             services/ (grep-verified after deleting the last direct
 *             fire-and-forget {@code PlatformEventPublisher.publish()} call
 *             sites — the {@code order/saga/OrderSaga} demo stub). Kept one
 *             release for API compatibility of downstream wiring, and BOTH
 *             {@link #forward(OutboxEvent)}'s Kafka branch (no ack wait) is
 *             exactly the fire-and-forget pattern V-09 flagged. New code must
 *             not call it; removal is scheduled with the next platform
 *             breaking batch.
 */
@SuppressWarnings("DeprecatedIsStillUsed") // zero usage — see @deprecated note
@Deprecated(forRemoval = true)
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalEventBridge {

    private final ExternalEventsProperties externalEventsProperties;
    private final ObjectProvider<KafkaPlatformEventPublisher> kafkaPublisher;

    public void forward(OutboxEvent event) {
        if (!externalEventsProperties.isEnabled()) {
            return;
        }
        if (externalEventsProperties.isKafkaEnabled()) {
            PlatformEventMessage envelope = toEnvelope(event);
            kafkaPublisher.ifAvailable(p -> p.publish(envelope));
            return;
        }
        log.info("EXTERNAL_EVENT | type={} | aggregateId={} | payload={}",
                event.getEventType(), event.getAggregateId(), event.getPayload());
    }

    /**
     * Synchronous forward used by the outbox poller so an event is only
     * marked PUBLISHED after the external system acknowledges receipt.
     * Falls back to fire-and-forget ({@link #forward}) for non-Kafka backends.
     */
    public void forwardForResult(OutboxEvent event) {
        if (!externalEventsProperties.isEnabled()) {
            return;
        }
        if (externalEventsProperties.isKafkaEnabled()) {
            PlatformEventMessage envelope = toEnvelope(event);
            kafkaPublisher.ifAvailable(publisher -> {
                boolean acked = publisher.publishForResult(envelope);
                if (!acked) {
                    throw new IllegalStateException(
                            "Failed to publish event to Kafka: " + event.getEventType());
                }
            });
            return;
        }
        forward(event);
    }

    private PlatformEventMessage toEnvelope(OutboxEvent event) {
        return PlatformEventMessage.of(
                event.getEventType(),
                String.valueOf(event.getAggregateId()),
                event.getPayload());
    }
}
