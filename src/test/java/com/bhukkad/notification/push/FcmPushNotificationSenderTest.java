package com.bhukkad.notification.push;

import com.bhukkad.config.NotificationProperties;
import com.bhukkad.entity.DeviceToken;
import com.bhukkad.repository.DeviceTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FcmPushNotificationSenderTest {

    @Mock
    private DeviceTokenRepository deviceTokenRepository;
    @Mock
    private RestTemplate restTemplate;

    private NotificationProperties props;
    private FcmPushNotificationSender sender;

    @BeforeEach
    void setUp() {
        props = new NotificationProperties();
        props.getPush().getFcm().setServerKey("fcm-key");
        sender = new FcmPushNotificationSender(props, deviceTokenRepository, new ObjectMapper(), restTemplate);
    }

    @Test void sendToUser_sendsToAllTokens() {
        DeviceToken token = new DeviceToken();
        token.setToken("fcm-token-1");
        when(deviceTokenRepository.findByUserIdAndActiveTrue(1L)).thenReturn(List.of(token));
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(null);

        sender.sendToUser(1L, "Title", "Body");

        verify(deviceTokenRepository).findByUserIdAndActiveTrue(1L);
        verify(restTemplate).postForEntity(
                org.mockito.ArgumentMatchers.startsWith("https://fcm.googleapis.com/"),
                any(),
                eq(String.class));
    }

    @Test void sendToUser_noTokens_skips() {
        when(deviceTokenRepository.findByUserIdAndActiveTrue(1L)).thenReturn(List.of());

        sender.sendToUser(1L, "Title", "Body");

        verify(restTemplate, org.mockito.Mockito.never())
                .postForEntity(anyString(), any(), eq(String.class));
    }

    @Test void sendToUser_missingServerKey_skips() {
        FcmPushNotificationSender withoutKey = new FcmPushNotificationSender(
                new NotificationProperties(), deviceTokenRepository, new ObjectMapper(), restTemplate);
        withoutKey.sendToUser(1L, "Title", "Body");
        verify(restTemplate, org.mockito.Mockito.never())
                .postForEntity(anyString(), any(), eq(String.class));
    }

    @Test void circuitBreakerFallback_doesNotThrow() {
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                sender, "pushUnavailable", 1L, "Title", "Body", new RuntimeException("fcm down"));
    }
}