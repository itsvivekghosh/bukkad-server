package com.bhukkad.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Observes {@link PaymentWebhookReceivedEvent} published by the outbox
 * processor.
 *
 * <p>Prior to the fix, {@code PAYMENT_WEBHOOK_RECEIVED} had no outbox routing
 * case, so it threw {@code IllegalArgumentException}, exhausted retries and
 * dead-lettered on every webhook. The event is now routed here: the durable
 * outbox row is marked {@code PUBLISHED} and an audit counter is incremented
 * so webhook throughput is visible in Prometheus instead of polluting the DLQ.
 */
@Slf4j
@Component
public class PaymentWebhookEventListener {

    private final Counter webhookReceivedCounter;

    public PaymentWebhookEventListener(MeterRegistry meterRegistry) {
        this.webhookReceivedCounter = Counter.builder("bhukkad.payment.webhook.outbox.received")
                .description("PAYMENT_WEBHOOK_RECEIVED outbox events routed by the processor")
                .register(meterRegistry);
    }

    @EventListener
    public void onPaymentWebhookReceived(PaymentWebhookReceivedEvent event) {
        webhookReceivedCounter.increment();
        log.debug("PAYMENT_WEBHOOK_RECEIVED | eventId={} | gatewayOrderId={} | gatewayPaymentId={}",
                event.eventId(), event.gatewayOrderId(), event.gatewayPaymentId());
    }
}
