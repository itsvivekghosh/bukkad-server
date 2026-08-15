package com.bhukkad.notification.whatsapp;

import com.bhukkad.config.NotificationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.whatsapp.provider", havingValue = "twilio")
public class TwilioWhatsAppSender implements WhatsAppSender {

    private final NotificationProperties notificationProperties;
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public void send(String phoneNumber, String body) {
        if (!StringUtils.hasText(phoneNumber)) {
            return;
        }
        NotificationProperties.Twilio twilio = notificationProperties.getWhatsapp().getTwilio();
        String from = StringUtils.hasText(twilio.getWhatsappFromNumber())
                ? twilio.getWhatsappFromNumber()
                : twilio.getFromNumber();
        if (!StringUtils.hasText(twilio.getAccountSid())
                || !StringUtils.hasText(twilio.getAuthToken())
                || !StringUtils.hasText(from)) {
            log.warn("Twilio WhatsApp credentials not configured");
            return;
        }

        String to = phoneNumber.startsWith("whatsapp:") ? phoneNumber : "whatsapp:" + phoneNumber;
        String fromAddr = from.startsWith("whatsapp:") ? from : "whatsapp:" + from;
        String url = "https://api.twilio.com/2010-04-01/Accounts/" + twilio.getAccountSid() + "/Messages.json";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String credentials = twilio.getAccountSid() + ":" + twilio.getAuthToken();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", to);
        form.add("From", fromAddr);
        form.add("Body", body);

        try {
            restTemplate.postForEntity(url, new HttpEntity<>(form, headers), String.class);
            log.info("Twilio WhatsApp sent | to={}", phoneNumber);
        } catch (Exception ex) {
            log.error("Twilio WhatsApp failed | to={} | error={}", phoneNumber, ex.getMessage());
        }
    }
}
