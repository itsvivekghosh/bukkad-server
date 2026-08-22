package com.bhukkad.notification.push;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LogPushNotificationSenderTest {
    private final LogPushNotificationSender sender = new LogPushNotificationSender();

    @Test void sendToUser_logsAndDoesNotThrow() {
        assertDoesNotThrow(() -> sender.sendToUser(1L, "Hello", "Push body"));
    }

    @Test void sendToUser_nullUserId_doesNotThrow() {
        assertDoesNotThrow(() -> sender.sendToUser(null, "Test", "Body"));
    }
}