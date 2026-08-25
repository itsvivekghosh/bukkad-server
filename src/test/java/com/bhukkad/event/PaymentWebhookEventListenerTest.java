package com.bhukkad.event;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentWebhookEventListenerTest {

    @Test
    void onPaymentWebhookReceived_incrementsCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentWebhookEventListener listener = new PaymentWebhookEventListener(registry);

        listener.onPaymentWebhookReceived(new PaymentWebhookReceivedEvent("evt_1", "order_1", "pay_1"));
        listener.onPaymentWebhookReceived(new PaymentWebhookReceivedEvent("evt_2", "order_2", "pay_2"));

        double count = registry.counter("bhukkad.payment.webhook.outbox.received").count();
        assertEquals(2.0, count);
    }

    @Test
    void onPaymentWebhookReceived_handlesNullIds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentWebhookEventListener listener = new PaymentWebhookEventListener(registry);

        // The webhook outbox payload may carry a null gateway order id when the
        // id is not numeric; the listener must not throw.
        listener.onPaymentWebhookReceived(new PaymentWebhookReceivedEvent(null, null, "pay_1"));

        assertEquals(1.0, registry.counter("bhukkad.payment.webhook.outbox.received").count());
    }
}
