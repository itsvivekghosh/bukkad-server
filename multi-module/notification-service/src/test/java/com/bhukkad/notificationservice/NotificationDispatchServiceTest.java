package com.bhukkad.notificationservice;

import com.bhukkad.notificationservice.channel.EmailSender;
import com.bhukkad.notificationservice.channel.push.PushNotificationSender;
import com.bhukkad.notificationservice.channel.sms.SmsSender;
import com.bhukkad.notificationservice.channel.whatsapp.WhatsAppSender;
import com.bhukkad.notificationservice.dispatch.NotificationDispatchService;
import com.bhukkad.notificationservice.event.NotificationRequestEvent;
import com.bhukkad.notificationservice.preference.NotificationPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock
    private EmailSender emailSender;
    @Mock
    private SmsSender smsSender;
    @Mock
    private WhatsAppSender whatsAppSender;
    @Mock
    private PushNotificationSender pushNotificationSender;
    @Mock
    private NotificationPreferenceService preferenceService;

    private NotificationDispatchService dispatchService;

    @BeforeEach
    void setUp() {
        dispatchService = new NotificationDispatchService(
                emailSender, smsSender, whatsAppSender, pushNotificationSender, preferenceService);
    }

    @Test
    void routesEmailWhenPreferenceEnabled() {
        when(preferenceService.isEmailEnabled(7L)).thenReturn(true);
        NotificationRequestEvent event = new NotificationRequestEvent(
                "e1", 1L, 7L, "EMAIL", "a@b.c", "Subject", "Body", "order-service");

        dispatchService.dispatch(event);

        verify(emailSender).send("a@b.c", "Subject", "Body");
        verify(smsSender, never()).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsEmailWhenPreferenceDisabled() {
        when(preferenceService.isEmailEnabled(7L)).thenReturn(false);
        NotificationRequestEvent event = new NotificationRequestEvent(
                "e2", 1L, 7L, "EMAIL", "a@b.c", "Subject", "Body", "order-service");

        dispatchService.dispatch(event);

        verify(emailSender, never()).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void routesSmsWhenPreferenceEnabled() {
        when(preferenceService.isSmsEnabled(7L)).thenReturn(true);
        NotificationRequestEvent event = new NotificationRequestEvent(
                "e3", null, 7L, "SMS", "+911234567890", null, "Body", "auth-service");

        dispatchService.dispatch(event);

        verify(smsSender).send("+911234567890", "Body");
        verify(emailSender, never()).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void routesPushWhenPreferenceEnabled() {
        when(preferenceService.isPushEnabled(7L)).thenReturn(true);
        NotificationRequestEvent event = new NotificationRequestEvent(
                "e4", null, 7L, "PUSH", null, "Title", "Body", "order-service");

        dispatchService.dispatch(event);

        verify(pushNotificationSender).sendToUser(7L, "Title", "Body");
    }

    @Test
    void skipsUnknownChannel() {
        NotificationRequestEvent event = new NotificationRequestEvent(
                "e5", null, 7L, "CARRIER_PIGEON", "x", "s", "b", "test");

        dispatchService.dispatch(event);

        verify(emailSender, never()).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(smsSender, never()).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsNullRequest() {
        dispatchService.dispatch(null);

        verify(emailSender, never()).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
