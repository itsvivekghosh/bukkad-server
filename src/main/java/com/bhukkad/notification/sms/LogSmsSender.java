package com.bhukkad.notification.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.sms.provider", havingValue = "log", matchIfMissing = true)
public class LogSmsSender implements SmsSender {

    @Override
    public void send(String phoneNumber, String body) {
        log.info("SMS | to={} | body={}", phoneNumber, body);
    }
}
