package com.bhukkad.event;

import com.bhukkad.config.ExternalEventsProperties;
import com.bhukkad.event.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

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
            kafkaPublisher.ifAvailable(p -> p.publish(event));
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
            kafkaPublisher.ifAvailable(publisher -> {
                try {
                    publisher.publishForResult(event);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Kafka ack", e);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to publish event to Kafka: " + event.getEventType(), e);
                }
            });
            return;
        }
        forward(event);
    }
}
