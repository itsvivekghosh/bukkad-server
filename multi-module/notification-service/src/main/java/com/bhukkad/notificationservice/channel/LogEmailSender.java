package com.bhukkad.notificationservice.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Log-only email sender used when SMTP is not configured (local/CI). Keeps the
 * delivery pipeline exercised end-to-end without an external mail server.
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "app.notification.email.enabled", havingValue = "false", matchIfMissing = true)
public class LogEmailSender implements EmailSender {

    @Override
    public void send(String to, String subject, String body) {
        log.info("EMAIL_NOTIFICATION | to={} | subject={} | body={}", to, subject, body);
    }
}
