package com.bhukkad.notification.infrastructure.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dev/no-credential providers: log-only senders must always report success
 * (SMS/Push/WhatsApp fan-out stays observable without Twilio keys).
 */
class LogSendersTest {

    @Test
    void logSmsSender_acceptsSend() {
        assertThat(new LogSmsSender().send("+919999999999", "Your order is out for delivery"))
                .isTrue();
    }

    @Test
    void logWhatsAppSender_acceptsSend() {
        assertThat(new LogWhatsAppSender().send("+91 99999-99999", "Order confirmed"))
                .isTrue();
    }

    @Test
    void logPushSender_completesSilently() {
        new LogPushNotificationSender().sendToUser(42L, "Order delivered", "Rate us!");
    }
}
