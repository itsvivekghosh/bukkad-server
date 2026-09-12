package com.bhukkad.notification.infrastructure.client;

import com.bhukkad.common.util.LogRedactor;
import com.bhukkad.notification.domain.service.WhatsAppSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.whatsapp.provider", havingValue = "log", matchIfMissing = true)
public class LogWhatsAppSender implements WhatsAppSender {

    @Override
    public boolean send(String phoneNumber, String body) {
        log.info("WHATSAPP | to={} | body={}", LogRedactor.maskE164(phoneNumber), body);
        return true;
    }
}