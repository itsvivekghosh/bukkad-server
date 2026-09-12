package com.bhukkad.notification.domain.service;

import com.bhukkad.notification.domain.entity.Notification;

/**
 * Channel dispatch strategy (port of monolith push/SMS/WhatsApp senders).
 * Implementations simulate provider delivery; production swaps in FCM/SES/Twilio
 * adapters.
 */
public interface NotificationChannel {

    boolean supports(String channel);

    void send(Notification notification);
}