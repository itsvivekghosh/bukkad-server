package com.bhukkad.notification.whatsapp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.whatsapp.provider", havingValue = "log", matchIfMissing = true)
public class LogWhatsAppSender implements WhatsAppSender {

    @Override
    public void send(String phoneNumber, String body) {
        log.info("WHATSAPP | to={} | body={}", phoneNumber, body);
    }
}
