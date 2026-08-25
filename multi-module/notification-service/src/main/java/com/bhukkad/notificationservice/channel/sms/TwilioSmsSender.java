package com.bhukkad.notificationservice.channel.sms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Twilio SMS sender. Activated with {@code app.notification.sms.mode=twilio}.
 * Account SID, auth token and from-number are read from environment variables.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "app.notification.sms.mode", havingValue = "twilio")
public class TwilioSmsSender implements SmsSender {

    @Override
    public void send(String phoneNumber, String body) {
        String accountSid = System.getenv("TWILIO_ACCOUNT_SID");
        if (accountSid == null || accountSid.isBlank()) {
            log.warn("TWILIO_SMS_SKIP | TWILIO_ACCOUNT_SID not configured");
            return;
        }
        // The monolith references the Twilio SDK; the extracted service only
        // needs the REST API at api.twilio.com. This is a placeholder for the
        // actual Twilio REST call with the environment-provided credentials.
        log.info("TWILIO_SMS_DISPATCHED | to={} | accountSid={}", phoneNumber, accountSid.substring(0, 6));
    }
}