package com.bhukkad.notification.sms;

import com.bhukkad.config.NotificationProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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

/**
 * Sends SMS via the Twilio REST API behind a circuit breaker.
 *
 * <p>Twilio is an external dependency: a slow or failing provider must not stall
 * the business transaction that triggered the notification. The circuit breaker
 * opens after repeated failures and the fallback degrades to a WARN log instead
 * of throwing, mirroring the "fire-and-forget" contract of the notification
 * pipeline.</p>
 *
 * <p>The {@link RestTemplate} is the application-wide configured bean, so the
 * connect/read timeouts from {@code app.http.*} apply — a hung Twilio call can
 * no longer block a thread indefinitely.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.sms.provider", havingValue = "twilio")
public class TwilioSmsSender implements SmsSender {

    private final NotificationProperties notificationProperties;
    private final RestTemplate restTemplate;

    public TwilioSmsSender(NotificationProperties notificationProperties,
                           RestTemplate restTemplate) {
        this.notificationProperties = notificationProperties;
        this.restTemplate = restTemplate;
    }

    @Override
    @CircuitBreaker(name = "notificationSms", fallbackMethod = "smsUnavailable")
    public void send(String phoneNumber, String body) {
        if (!StringUtils.hasText(phoneNumber)) {
            log.warn("Skipping SMS — no phone number");
            return;
        }
        NotificationProperties.Twilio twilio = notificationProperties.getSms().getTwilio();
        if (!StringUtils.hasText(twilio.getAccountSid())
                || !StringUtils.hasText(twilio.getAuthToken())
                || !StringUtils.hasText(twilio.getFromNumber())) {
            log.warn("Twilio credentials not configured; SMS not sent");
            return;
        }

        String url = "https://api.twilio.com/2010-04-01/Accounts/" + twilio.getAccountSid() + "/Messages.json";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String credentials = twilio.getAccountSid() + ":" + twilio.getAuthToken();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", phoneNumber);
        form.add("From", twilio.getFromNumber());
        form.add("Body", body);

        restTemplate.postForEntity(url, new HttpEntity<>(form, headers), String.class);
        log.info("Twilio SMS sent | to={}", phoneNumber);
    }

    /**
     * Circuit-breaker fallback: the notification pipeline is fire-and-forget, so a
     * failing provider degrades to a WARN instead of propagating the failure to
     * the business call that triggered it.
     */
    @SuppressWarnings("unused")
    void smsUnavailable(String phoneNumber, String body, Throwable ex) {
        log.warn("Twilio SMS unavailable (circuit open) | to={} | error={}",
                phoneNumber, ex.getMessage());
    }
}
