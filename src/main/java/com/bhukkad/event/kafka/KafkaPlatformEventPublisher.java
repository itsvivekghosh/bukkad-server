package com.bhukkad.event.kafka;

import com.bhukkad.config.ExternalEventsProperties;
import com.bhukkad.outbox.OutboxEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
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

    /**
     * Fire-and-forget publish (backward-compatible). Failures are logged
     * and swallowed so outbox processing is not blocked by transient Kafka errors.
     */
    public void publish(OutboxEvent event) {
        try {
            ProducerRecord<String, String> record = buildRecord(event);
            kafkaTemplate.send(record);
            log.debug("KAFKA_EVENT_PUBLISHED | topic={} | type={} | aggregateId={}",
                    externalEventsProperties.getKafka().getPlatformTopic(),
                    event.getEventType(), event.getAggregateId());
        } catch (Exception ex) {
            log.error("KAFKA_EVENT_PUBLISH_FAILED | type={} | error={}",
                    event.getEventType(), ex.getMessage(), ex);
        }
    }

    /**
     * Synchronous publish that waits for the broker ACK. Used by
     * {@code OutboxEventProcessor} so that outbox events are not marked
     * PUBLISHED until Kafka confirms receipt.
     *
     * @return the Kafka send result on success
     * @throws Exception if the send fails or times out
     */
    public SendResult<String, String> publishForResult(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record = buildRecord(event);
        return kafkaTemplate.send(record).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private ProducerRecord<String, String> buildRecord(OutboxEvent event) {
        try {
            PlatformEventMessage message = new PlatformEventMessage(
                    event.getEventType(),
                    event.getAggregateId(),
                    event.getPayload(),
                    Instant.now());
            String json = objectMapper.writeValueAsString(message);
            String topic = externalEventsProperties.getKafka().getPlatformTopic();
            // Propagate W3C traceparent in Kafka headers so downstream consumers
            // (other services) continue the same trace (Phase 4 observability).
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, event.getEventType(), json);
            String traceId = com.bhukkad.logging.TraceContext.getTraceId();
            if (traceId != null && !traceId.isBlank()) {
                record.headers().add("traceparent",
                        ("00-" + traceId + "-" + com.bhukkad.logging.TraceContext.newSpanId() + "-01")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return record;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize event: " + event.getEventType(), ex);
        }
    }
}
