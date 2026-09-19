package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerAwareListenerErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Logs Kafka consumer lag metrics to help detect stuck or slow consumers.
 * This is a lightweight alternative to full consumer lag monitoring.
 */
@Component
public class KafkaLagLogger {

    private static final Logger log = LoggerFactory.getLogger("KAFKA_LAG");
    private static final long LAG_WARN_THRESHOLD = 1000;

    private final Map<String, Long> lastProcessedOffset = new HashMap<>();
    private final Map<String, Long> lastLogEndOffset = new HashMap<>();

    @KafkaListener(id = "kafka-lag-logger",
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
            groupId = "kafka-lag-logger")
    public void trackLag(@Payload String event,
                         @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                         @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                         @Header(KafkaHeaders.OFFSET) long offset) {
        String key = topic + "-" + partition;
        lastProcessedOffset.put(key, offset);
    }

    public void logCurrentLag(String topic, int partition, long logEndOffset) {
        String key = topic + "-" + partition;
        Long processed = lastProcessedOffset.get(key);
        
        if (processed != null) {
            long lag = logEndOffset - processed;
            if (lag > LAG_WARN_THRESHOLD) {
                log.warn("KAFKA_LAG | topic={} | partition={} | lag={} | traceId={}",
                    topic, partition, lag, TraceContext.currentTraceId());
            } else if (log.isDebugEnabled()) {
                log.debug("KAFKA_LAG | topic={} | partition={} | lag={}",
                    topic, partition, lag);
            }
        }
    }

    /**
     * Error handler that logs consumer failures with context.
     */
    public ConsumerAwareListenerErrorHandler kafkaErrorHandler() {
        return (message, exception, consumer) -> {
            if (message != null && message.getPayload() instanceof ConsumerRecord<?, ?> record) {
                log.error("KAFKA_CONSUMER_ERROR | topic={} | partition={} | offset={} | error={} | traceId={}",
                    record.topic(), record.partition(), record.offset(),
                    exception.getMessage(), TraceContext.currentTraceId());
            } else if (message != null) {
                log.error("KAFKA_CONSUMER_ERROR | payload={} | error={} | traceId={}",
                    message.getPayload(), exception.getMessage(), TraceContext.currentTraceId());
            }
            return null;
        };
    }
}
