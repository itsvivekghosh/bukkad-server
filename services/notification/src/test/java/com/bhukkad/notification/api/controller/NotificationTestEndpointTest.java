package com.bhukkad.notification.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.notification.api.dto.request.TestNotificationRequest;
import com.bhukkad.notification.domain.entity.Notification;
import com.bhukkad.notification.domain.service.impl.NotificationDispatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin "test notification" endpoint: null-body defaults, blank-recipient
 * rejection and the status fallback in the response envelope.
 */
@ExtendWith(MockitoExtension.class)
class NotificationTestEndpointTest {

    @Mock private NotificationDispatchService dispatchService;
    @InjectMocks private NotificationController controller;

    @Test
    void nullBody_defaultsEmailChannel_andRejectsBlankRecipient() {
        assertThatThrownBy(() -> controller.test(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("recipient is required");
    }

    @Test
    void blankRecipient_rejected() {
        assertThatThrownBy(() -> controller.test(new TestNotificationRequest("SMS", "   ")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void dispatchesFixedTestTemplate_andReportsStatus() {
        Notification notification = new Notification();
        notification.setId(77L);
        notification.setStatus(Notification.STATUS_SENT);
        when(dispatchService.dispatch("SMS", "919999999999", "TEST",
                "Bhukkad test notification",
                "This is a test notification triggered from the admin console."))
                .thenReturn(notification);

        Map<String, Object> body = controller.test(new TestNotificationRequest("SMS", "919999999999"));

        assertThat(body)
                .containsEntry("notificationId", 77L)
                .containsEntry("channel", "SMS")
                .containsEntry("recipient", "919999999999")
                .containsEntry("status", Notification.STATUS_SENT)
                .containsEntry("message", "Test notification dispatched");
        verify(dispatchService).dispatch("SMS", "919999999999", "TEST",
                "Bhukkad test notification",
                "This is a test notification triggered from the admin console.");
    }

    @Test
    void missingStatusOnNotification_defaultsToSent() {
        Notification notification = new Notification();
        notification.setId(78L);
        when(dispatchService.dispatch("EMAIL", "ops@example.com", "TEST",
                "Bhukkad test notification",
                "This is a test notification triggered from the admin console."))
                .thenReturn(notification);

        assertThat(controller.test(new TestNotificationRequest("EMAIL", "ops@example.com")))
                .containsEntry("status", "SENT");
    }
}
