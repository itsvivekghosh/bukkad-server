package com.bhukkad.notificationservice.dispatch;

import com.bhukkad.notificationservice.channel.EmailSender;
import com.bhukkad.notificationservice.channel.push.PushNotificationSender;
import com.bhukkad.notificationservice.channel.sms.SmsSender;
import com.bhukkad.notificationservice.channel.whatsapp.WhatsAppSender;
import com.bhukkad.notificationservice.event.NotificationRequestEvent;
import com.bhukkad.notificationservice.preference.NotificationPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Routes a {@link NotificationRequestEvent} to the channel sender that matches
 * the requested channel, honouring the recipient's preferences. Delivery is
 * fire-and-forget: channel senders are resilient (circuit breakers / logging)
 * and never throw to the caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final WhatsAppSender whatsAppSender;
    private final PushNotificationSender pushNotificationSender;
    private final NotificationPreferenceService preferenceService;

    public void dispatch(NotificationRequestEvent request) {
        if (request == null || request.channel() == null) {
            log.warn("NOTIFICATION_DISPATCH_SKIP | missing channel");
            return;
        }
        switch (request.channel().toUpperCase()) {
            case "EMAIL" -> sendEmail(request);
            case "SMS" -> sendSms(request);
            case "WHATSAPP" -> sendWhatsApp(request);
            case "PUSH" -> sendPush(request);
            default -> log.warn("NOTIFICATION_DISPATCH_SKIP | unknown channel={}", request.channel());
        }
    }

    private void sendEmail(NotificationRequestEvent r) {
        if (!preferenceService.isEmailEnabled(r.userId())) {
            return;
        }
        if (r.recipient() == null) {
            log.warn("NOTIFICATION_DISPATCH_SKIP | email without recipient");
            return;
        }
        emailSender.send(r.recipient(), r.subject(), r.body());
    }

    private void sendSms(NotificationRequestEvent r) {
        if (!preferenceService.isSmsEnabled(r.userId())) {
            return;
        }
        if (r.recipient() == null) {
            log.warn("NOTIFICATION_DISPATCH_SKIP | sms without recipient");
            return;
        }
        smsSender.send(r.recipient(), r.body());
    }

    private void sendWhatsApp(NotificationRequestEvent r) {
        if (!preferenceService.isWhatsappEnabled(r.userId())) {
            return;
        }
        if (r.recipient() == null) {
            log.warn("NOTIFICATION_DISPATCH_SKIP | whatsapp without recipient");
            return;
        }
        whatsAppSender.send(r.recipient(), r.body());
    }

    private void sendPush(NotificationRequestEvent r) {
        if (r.userId() == null) {
            log.warn("NOTIFICATION_DISPATCH_SKIP | push without userId");
            return;
        }
        if (!preferenceService.isPushEnabled(r.userId())) {
            return;
        }
        pushNotificationSender.sendToUser(r.userId(), r.subject(), r.body());
    }
}
