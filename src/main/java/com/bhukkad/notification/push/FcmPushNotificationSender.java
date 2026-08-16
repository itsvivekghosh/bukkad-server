package com.bhukkad.notification.push;

import com.bhukkad.config.NotificationProperties;
import com.bhukkad.repository.DeviceTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.push.provider", havingValue = "fcm")
public class FcmPushNotificationSender implements PushNotificationSender {

    private final NotificationProperties notificationProperties;
    private final DeviceTokenRepository deviceTokenRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
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
                log.error("FCM push failed | userId={} | error={}", userId, ex.getMessage());
            }
        }
    }
}
