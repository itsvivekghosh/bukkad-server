package com.bhukkad.notification.push;

public interface PushNotificationSender {
    void sendToUser(Long userId, String title, String body);
}
