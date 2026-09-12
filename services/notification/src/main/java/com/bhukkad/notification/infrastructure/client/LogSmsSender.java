package com.bhukkad.notification.infrastructure.client;

import com.bhukkad.common.util.LogRedactor;
import com.bhukkad.notification.domain.service.SmsSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.sms.provider", havingValue = "log", matchIfMissing = true)
public class LogSmsSender implements SmsSender {

    @Override
    public boolean send(String phoneNumber, String body) {
        log.info("SMS | to={} | body={}", LogRedactor.maskE164(phoneNumber), body);
        return true;
    }
}