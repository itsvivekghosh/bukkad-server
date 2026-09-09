package com.bhukkad.identity.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Publishes identity domain events via the transactional outbox (plan §6.2,
 * {@code identity.events.v1}): {@code CustomerRegistered},
 * {@code CustomerUpdated}, {@code AddressChanged}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityEventPublisher {

    public static final String TOPIC = "identity.events.v1";
    public static final String TYPE_CUSTOMER_REGISTERED = "CustomerRegistered";
    public static final String TYPE_ADDRESS_CHANGED = "AddressChanged";

    private final OutboxClient outboxClient;

    public void customerRegistered(Long customerId, String email, String fullName) {
        String payload = "{\"id\":%d,\"email\":\"%s\",\"fullName\":\"%s\"}"
                .formatted(customerId, email, fullName);
        enqueue(TYPE_CUSTOMER_REGISTERED, customerId, payload);
    }

    public void addressChanged(Long customerId, Long addressId) {
        enqueue(TYPE_ADDRESS_CHANGED, customerId,
                "{\"customerId\":%d,\"addressId\":%d}".formatted(customerId, addressId));
    }

    private void enqueue(String type, Long aggregateId, String payload) {
        try {
            PlatformEventMessage message = PlatformEventMessage.of(type, String.valueOf(aggregateId), payload);
            outboxClient.enqueue(message, aggregateId);
            log.info("IDENTITY_EVENT_ENQUEUED | type={} | customerId={}", type, aggregateId);
        } catch (Exception e) {
            log.error("IDENTITY_EVENT_ENQUEUE_FAILED | type={} | customerId={}", type, aggregateId, e);
        }
    }
}