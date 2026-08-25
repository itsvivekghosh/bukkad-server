package com.bhukkad.notificationservice.channel.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Log-only push sender used when FCM is not configured (local/CI) or as the
 * default fallback. Keeps the delivery pipeline exercised end-to-end without
 * depending on an external push provider.
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "app.notification.push.mode", havingValue = "log", matchIfMissing = true)
public class LogPushNotificationSender implements PushNotificationSender {

    @Override
    public void sendToUser(Long userId, String title, String body) {
        log.info("PUSH_NOTIFICATION | userId={} | title={} | body={}", userId, title, body);
    }
}
