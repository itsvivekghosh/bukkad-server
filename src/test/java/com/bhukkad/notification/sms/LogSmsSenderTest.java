package com.bhukkad.notification.sms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LogSmsSenderTest {
    private final LogSmsSender sender = new LogSmsSender();

    @Test void send_logsAndDoesNotThrow() {
        assertDoesNotThrow(() -> sender.send("+911234567890", "Hello from LogSmsSender"));
    }

    @Test void send_nullPhone_doesNotThrow() {
        assertDoesNotThrow(() -> sender.send(null, "test"));
    }

    @Test void send_emptyBody_doesNotThrow() {
        assertDoesNotThrow(() -> sender.send("+911234567890", ""));
    }
}