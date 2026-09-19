package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Audits Kafka message consumption and production. Useful for debugging
 * event-driven flows and detecting stuck/DLT messages.
 *
 * <p>Active only in dev/test profiles to avoid log noise in production.</p>
 */
@Component
@Profile("dev")
public class KafkaEventLogger {

    private static final Logger log = LoggerFactory.getLogger("KAFKA_EVENTS");

    /**
     * Logs every consumed event at DEBUG level. Adjust as needed for your
     * service's topics.
     */
    @KafkaListener(id = "kafka-event-logger",
            topics = {
        "social.events.v1",
        "order.events.v1",
        "payment.events.v1",
        "delivery.events.v1",
        "restaurant.events.v1",
        "notification.events.v1",
        "survey.events.v1",
        "referral.events.v1"
    },
            groupId = "kafka-event-logger")
    public void logConsumedEvent(@Payload String event,
                                 @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                                 @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                 @Header(KafkaHeaders.OFFSET) long offset,
                                 @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        if (log.isDebugEnabled()) {
            log.debug("KAFKA_EVENT_CONSUMED | topic={} | partition={} | offset={} | key={} | payload={} | traceId={}",
                topic, partition, offset, key, truncate(event, 1024), TraceContext.currentTraceId());
        }
    }

    /**
     * Logs produced events. Wire this into your event publishers if you want
     * outbound event visibility.
     */
    public void logProducedEvent(String topic, String key, String payload) {
        if (log.isDebugEnabled()) {
            log.debug("KAFKA_EVENT_PRODUCED | topic={} | key={} | payload={} | traceId={}",
                topic, key, truncate(payload, 1024), TraceContext.currentTraceId());
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() > maxLength) {
            return value.substring(0, maxLength) + "...[truncated " + (value.length() - maxLength) + " chars]";
        }
        return value;
    }
}
