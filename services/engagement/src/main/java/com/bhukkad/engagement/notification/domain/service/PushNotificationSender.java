package com.bhukkad.engagement.notification.domain.service;

public interface PushNotificationSender {
    void sendToUser(Long userId, String title, String body);
}
