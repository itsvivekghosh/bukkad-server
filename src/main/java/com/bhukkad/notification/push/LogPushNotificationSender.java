package com.bhukkad.notification.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.push.provider", havingValue = "log", matchIfMissing = true)
public class LogPushNotificationSender implements PushNotificationSender {

    @Override
    public void sendToUser(Long userId, String title, String body) {
        log.info("PUSH | userId={} | title={} | body={}", userId, title, body);
    }
}
