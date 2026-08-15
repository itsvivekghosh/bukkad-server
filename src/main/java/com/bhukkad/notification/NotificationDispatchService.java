package com.bhukkad.notification;

import com.bhukkad.event.OrderAgentAssignedEvent;
import com.bhukkad.event.OrderCreatedEvent;
import com.bhukkad.event.OrderStatusChangedEvent;
import com.bhukkad.event.kafka.PlatformEventMessage;
import com.bhukkad.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public void dispatch(PlatformEventMessage message) {
        if (message == null || message.eventType() == null) {
            return;
        }
        try {
            switch (message.eventType()) {
                case "ORDER_CREATED" -> {
                    OrderCreatedEvent event = objectMapper.readValue(message.payload(), OrderCreatedEvent.class);
                    notificationService.sendOrderConfirmation(event.orderId());
                }
                case "ORDER_STATUS_CHANGED" -> {
                    OrderStatusChangedEvent event = objectMapper.readValue(message.payload(), OrderStatusChangedEvent.class);
                    notificationService.sendOrderStatusUpdate(event.orderId(), event.newStatus().name());
                }
                case "ORDER_AGENT_ASSIGNED" -> {
                    OrderAgentAssignedEvent event = objectMapper.readValue(message.payload(), OrderAgentAssignedEvent.class);
                    notificationService.sendDeliveryAssignment(event.orderId(), event.deliveryAgentId());
                }
                default -> log.debug("NOTIFICATION_DISPATCH_SKIP | type={}", message.eventType());
            }
        } catch (Exception ex) {
            log.error("NOTIFICATION_DISPATCH_FAILED | type={} | error={}",
                    message.eventType(), ex.getMessage(), ex);
        }
    }
}
