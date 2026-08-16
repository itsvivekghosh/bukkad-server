package com.bhukkad.event.kafka;

import com.bhukkad.config.ExternalEventsProperties;
import com.bhukkad.notification.NotificationDispatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnExpression("'${app.events.external.enabled:false}' == 'true' && '${app.events.external.type:log}' == 'kafka'")
@RequiredArgsConstructor
public class PlatformEventKafkaConsumer {

    private final NotificationDispatchService notificationDispatchService;
    private final ObjectMapper objectMapper;
    private final ExternalEventsProperties externalEventsProperties;

    @KafkaListener(
            topics = "${app.events.external.kafka.platform-topic}",
            groupId = "${app.events.external.kafka.consumer-group}")
    public void onPlatformEvent(String payload) {
        try {
            PlatformEventMessage message = objectMapper.readValue(payload, PlatformEventMessage.class);
            log.debug("KAFKA_EVENT_RECEIVED | type={} | aggregateId={}",
                    message.eventType(), message.aggregateId());
            if (isNotificationEvent(message.eventType())) {
                notificationDispatchService.dispatch(message);
            }
        } catch (Exception ex) {
            log.error("KAFKA_EVENT_CONSUME_FAILED | error={}", ex.getMessage(), ex);
        }
    }

    private boolean isNotificationEvent(String eventType) {
        return "ORDER_CREATED".equals(eventType)
                || "ORDER_STATUS_CHANGED".equals(eventType)
                || "ORDER_AGENT_ASSIGNED".equals(eventType);
    }
}
