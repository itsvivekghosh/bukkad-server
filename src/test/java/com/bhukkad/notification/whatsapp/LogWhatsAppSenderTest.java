package com.bhukkad.notification.whatsapp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LogWhatsAppSenderTest {
    private final LogWhatsAppSender sender = new LogWhatsAppSender();

    @Test void send_logsAndDoesNotThrow() {
        assertDoesNotThrow(() -> sender.send("+911234567890", "Hello from WhatsApp"));
    }

    @Test void send_nullPhone_doesNotThrow() {
        assertDoesNotThrow(() -> sender.send(null, "test"));
    }

    @Test void send_emptyBody_doesNotThrow() {
        assertDoesNotThrow(() -> sender.send("+911234567890", ""));
    }
}