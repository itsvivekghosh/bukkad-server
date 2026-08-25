package com.bhukkad.notificationservice.channel.push;

/**
 * Push notification channel. Implementations route to a concrete provider
 * (FCM) or to the log in non-production environments.
 */
public interface PushNotificationSender {

    /**
     * Sends a push notification to a user's registered device(s).
     *
     * @param userId target user id
     * @param title  notification title
     * @param body   notification body
     */
    void sendToUser(Long userId, String title, String body);
}
