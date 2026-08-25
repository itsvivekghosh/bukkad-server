package com.bhukkad.notificationservice.channel.push;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Firebase Cloud Messaging push sender. Activated with
 * {@code app.notification.push.mode=fcm}. Implemented as a thin HTTP client
 * against the FCM HTTP v1 API; the credential/token resolution is delegated to
 * the environment so no secret is baked into the image.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "app.notification.push.mode", havingValue = "fcm")
public class FcmPushNotificationSender implements PushNotificationSender {

    private final RestTemplate restTemplate;

    @Override
    public void sendToUser(Long userId, String title, String body) {
        // FCM HTTP v1 requires an OAuth token obtained from the service-account
        // key. Endpoint, project id and token are environment-provided.
        String endpoint = System.getenv("FCM_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("FCM_PUSH_SKIP | userId={} | FCM_ENDPOINT not configured", userId);
            return;
        }
        try {
            String payload = """
                    {"message":{"token":"%s","notification":{"title":"%s","body":"%s"}}}
                    """.formatted(System.getenv("FCM_DEVICE_TOKEN"), title, body);
            restTemplate.postForEntity(endpoint, payload, String.class);
        } catch (Exception ex) {
            log.warn("FCM_PUSH_FAILED | userId={} | error={}", userId, ex.getMessage());
        }
    }
}
