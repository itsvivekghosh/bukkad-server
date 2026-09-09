package com.bhukkad.notification.service;

import com.bhukkad.notification.domain.Notification;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingNotificationChannelTest {

    private final LoggingNotificationChannel channel = new LoggingNotificationChannel();

    @Test
    void supports_email() {
        assertThat(channel.supports(Notification.CHANNEL_EMAIL)).isTrue();
    }

    @Test
    void supports_sms() {
        assertThat(channel.supports(Notification.CHANNEL_SMS)).isTrue();
    }

    @Test
    void supports_push() {
        assertThat(channel.supports(Notification.CHANNEL_PUSH)).isTrue();
    }

    @Test
    void supports_unknownChannel_returnsFalse() {
        assertThat(channel.supports("FAX")).isFalse();
    }

    @Test
    void send_acceptsAnyNotificationWithoutError() {
        Notification notification = new Notification();
        notification.setChannel(Notification.CHANNEL_EMAIL);
        notification.setRecipient("a@b.com");
        notification.setTemplate("CONFIRM");

        assertThatCode(() -> channel.send(notification)).doesNotThrowAnyException();
    }
}
