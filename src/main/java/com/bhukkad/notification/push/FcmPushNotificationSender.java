package com.bhukkad.notification.push;

import com.bhukkad.config.NotificationProperties;
import com.bhukkad.repository.DeviceTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Sends push notifications via the FCM HTTP API behind a circuit breaker.
 *
 * <p>FCM is an external dependency; the circuit breaker prevents a slow or
 * failing provider from stalling the notification dispatch path. The fallback
 * degrades to a WARN log per token.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.push.provider", havingValue = "fcm")
public class FcmPushNotificationSender implements PushNotificationSender {

    private final NotificationProperties notificationProperties;
    private final DeviceTokenRepository deviceTokenRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public FcmPushNotificationSender(NotificationProperties notificationProperties,
                                     DeviceTokenRepository deviceTokenRepository,
                                     ObjectMapper objectMapper,
                                     RestTemplate restTemplate) {
        this.notificationProperties = notificationProperties;
        this.deviceTokenRepository = deviceTokenRepository;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    @Override
    @CircuitBreaker(name = "notificationPush", fallbackMethod = "pushUnavailable")
    public void sendToUser(Long userId, String title, String body) {
        String serverKey = notificationProperties.getPush().getFcm().getServerKey();
        if (!StringUtils.hasText(serverKey)) {
            log.warn("FCM server key not configured; push not sent");
            return;
        }

        List<String> tokens = deviceTokenRepository.findByUserIdAndActiveTrue(userId).stream()
                .map(token -> token.getToken())
                .toList();
        if (tokens.isEmpty()) {
            log.debug("No active device tokens for user {}", userId);
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(serverKey);

        for (String token : tokens) {
            try {
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("to", token);
                ObjectNode notification = payload.putObject("notification");
                notification.put("title", title);
                notification.put("body", body);
                restTemplate.postForEntity(
                        "https://fcm.googleapis.com/fcm/send",
                        new HttpEntity<>(payload.toString(), headers),
                        String.class);
            } catch (Exception ex) {
                log.warn("FCM push failed | userId={} | error={}", userId, ex.getMessage());
            }
        }
        log.info("FCM push sent | userId={} | tokens={}", userId, tokens.size());
    }

    @SuppressWarnings("unused")
    void pushUnavailable(Long userId, String title, String body, Throwable ex) {
        log.warn("FCM push unavailable (circuit open) | userId={} | error={}",
                userId, ex.getMessage());
    }
}
