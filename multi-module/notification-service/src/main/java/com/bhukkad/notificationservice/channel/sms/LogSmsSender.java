package com.bhukkad.notificationservice.channel.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Log-only SMS sender used when Twilio is not configured (local/CI). Keeps the
 * delivery pipeline exercised end-to-end without an external provider.
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "app.notification.sms.mode", havingValue = "log", matchIfMissing = true)
public class LogSmsSender implements SmsSender {

    @Override
    public void send(String phoneNumber, String body) {
        log.info("SMS_NOTIFICATION | to={} | body={}", phoneNumber, body);
    }
}
