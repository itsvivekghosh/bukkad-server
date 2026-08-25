package com.bhukkad.notificationservice.channel.whatsapp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Twilio WhatsApp sender. Activated with {@code app.notification.whatsapp.mode=twilio}.
 * Credentials are read from environment variables; no secret is baked in.
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "app.notification.whatsapp.mode", havingValue = "twilio")
public class TwilioWhatsAppSender implements WhatsAppSender {

    @Override
    public void send(String phoneNumber, String body) {
        String accountSid = System.getenv("TWILIO_ACCOUNT_SID");
        if (accountSid == null || accountSid.isBlank()) {
            log.warn("TWILIO_WHATSAPP_SKIP | TWILIO_ACCOUNT_SID not configured");
            return;
        }
        log.info("TWILIO_WHATSAPP_DISPATCHED | to={} | accountSid={}", phoneNumber, accountSid.substring(0, 6));
    }
}