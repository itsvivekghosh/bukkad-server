package com.bhukkad.notification.service;

import com.bhukkad.common.util.LogRedactor;
import com.bhukkad.notification.domain.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Simulated email/sms/push senders (port of monolith {@code ResilientEmailSender}
 * + push/SMS dispatch). Log-only delivery for dev; idempotency + retries are
 * owned by the dispatch service.
 */
@Slf4j
@Service
public class LoggingNotificationChannel implements NotificationChannel {

    @Override
    public boolean supports(String channel) {
        return Notification.CHANNEL_EMAIL.equals(channel)
                || Notification.CHANNEL_SMS.equals(channel)
                || Notification.CHANNEL_PUSH.equals(channel);
    }

    @Override
    public void send(Notification notification) {
        // V-21: the recipient may be a phone number or an email.
        String recipient = notification.getRecipient();
        String masked = recipient != null && recipient.contains("@")
                ? LogRedactor.maskEmail(recipient)
                : LogRedactor.maskE164(recipient);
        log.info("NOTIFICATION_CHANNEL_SEND | channel={} | recipient={} | template={}",
                notification.getChannel(), masked, notification.getTemplate());
    }
}