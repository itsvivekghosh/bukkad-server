package com.bhukkad.notificationservice.channel.whatsapp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Log-only WhatsApp sender used when Twilio WhatsApp is not configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "app.notification.whatsapp.mode", havingValue = "log", matchIfMissing = true)
public class LogWhatsAppSender implements WhatsAppSender {

    @Override
    public void send(String phoneNumber, String body) {
        log.info("WHATSAPP_NOTIFICATION | to={} | body={}", phoneNumber, body);
    }
}
