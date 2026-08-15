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
            kafkaPublisher.ifAvailable(publisher -> publisher.publish(event));
            return;
        }
        log.info("EXTERNAL_EVENT | type={} | aggregateId={} | payload={}",
                event.getEventType(), event.getAggregateId(), event.getPayload());
    }
}
