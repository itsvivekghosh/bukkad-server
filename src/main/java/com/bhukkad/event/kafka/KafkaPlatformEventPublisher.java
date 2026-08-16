package com.bhukkad.event.kafka;

import com.bhukkad.config.ExternalEventsProperties;
import com.bhukkad.outbox.OutboxEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@ConditionalOnExpression("'${app.events.external.enabled:false}' == 'true' && '${app.events.external.type:log}' == 'kafka'")
@RequiredArgsConstructor
public class KafkaPlatformEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExternalEventsProperties externalEventsProperties;
    private final ObjectMapper objectMapper;

    public void publish(OutboxEvent event) {
        try {
            PlatformEventMessage message = new PlatformEventMessage(
                    event.getEventType(),
                    event.getAggregateId(),
                    event.getPayload(),
                    Instant.now());
            String json = objectMapper.writeValueAsString(message);
            String topic = externalEventsProperties.getKafka().getPlatformTopic();
            kafkaTemplate.send(topic, event.getEventType(), json);
            log.debug("KAFKA_EVENT_PUBLISHED | topic={} | type={} | aggregateId={}",
                    topic, event.getEventType(), event.getAggregateId());
        } catch (Exception ex) {
            log.error("KAFKA_EVENT_PUBLISH_FAILED | type={} | error={}",
                    event.getEventType(), ex.getMessage(), ex);
        }
    }
}
