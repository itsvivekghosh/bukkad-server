package com.bhukkad.event.kafka;

import com.bhukkad.config.ExternalEventsProperties;
import com.bhukkad.notification.NotificationDispatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnExpression("'${app.events.external.enabled:false}' == 'true' && '${app.events.external.type:log}' == 'kafka'")
@RequiredArgsConstructor
public class PlatformEventKafkaConsumer {

    private final NotificationDispatchService notificationDispatchService;
    private final ObjectMapper objectMapper;
    private final ExternalEventsProperties externalEventsProperties;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @KafkaListener(
            topics = "${app.events.external.kafka.platform-topic}",
            groupId = "${app.events.external.kafka.consumer-group}")
    public void onPlatformEvent(String payload) {
        String eventType = null;
        try {
            PlatformEventMessage message = objectMapper.readValue(payload, PlatformEventMessage.class);
            eventType = message.eventType();
            log.debug("KAFKA_EVENT_RECEIVED | type={} | aggregateId={}",
                    message.eventType(), message.aggregateId());
            if (isNotificationEvent(message.eventType())) {
                notificationDispatchService.dispatch(message);
            }
        } catch (Exception ex) {
            // Route unprocessable messages to the DLQ topic so they are not
            // silently lost; the original message is not acknowledged via
            // auto-commit retries, but a copy is preserved for replay.
            log.error("KAFKA_EVENT_CONSUME_FAILED | type={} | error={}",
                    eventType != null ? eventType : "UNKNOWN", ex.getMessage(), ex);
            sendToDeadLetter(payload);
        }
    }

    private void sendToDeadLetter(String payload) {
        try {
            String dlqTopic = externalEventsProperties.getKafka().getDlqTopic();
            // Preserve the original payload verbatim on the DLQ topic; a
            // separate consumer/replay job can re-process it later.
            kafkaTemplate.send(dlqTopic, "DLQ", payload);
            log.warn("KAFKA_EVENT_DLQ_ROUTED | topic={}", dlqTopic);
        } catch (Exception dlqEx) {
            log.error("KAFKA_EVENT_DLQ_FAILED | error={}", dlqEx.getMessage(), dlqEx);
        }
    }

    private boolean isNotificationEvent(String eventType) {
        return "ORDER_CREATED".equals(eventType)
                || "ORDER_STATUS_CHANGED".equals(eventType)
                || "ORDER_AGENT_ASSIGNED".equals(eventType);
    }
}
