package com.bhukkad.common.outbox;

/**
 * Client contract for enqueueing events into the owning service's outbox.
 * Implementations write a row to {@code outbox_events} in the SAME local
 * transaction as the business write, guaranteeing atomicity of state + event.
 */
public interface OutboxClient {

    /**
     * Enqueues a platform event for asynchronous delivery.
     *
     * @param eventType     e.g. "ORDER_CREATED"
     * @param aggregateType e.g. "ORDER"
     * @param aggregateId   business id
     * @param payload       JSON payload
     */
    void enqueue(String eventType, String aggregateType, String aggregateId, String payload);
}
