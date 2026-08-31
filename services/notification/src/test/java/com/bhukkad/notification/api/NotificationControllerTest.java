package com.bhukkad.notification.api;

import com.bhukkad.notification.domain.Notification;
import com.bhukkad.notification.service.NotificationDispatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock private NotificationDispatchService dispatchService;
    @InjectMocks private NotificationController controller;

    @Test
    void dispatch_delegatesToDispatchService() {
        Notification notification = new Notification();
        notification.setId(1L);
        notification.setStatus(Notification.STATUS_SENT);
        when(dispatchService.dispatch("EMAIL", "a@b.com", "CONFIRM", "Hello", "Body"))
                .thenReturn(notification);

        NotificationController.DispatchRequest request =
                new NotificationController.DispatchRequest("EMAIL", "a@b.com", "CONFIRM", "Hello", "Body");

        assertThat(controller.dispatch(request).getStatus()).isEqualTo(Notification.STATUS_SENT);
        verify(dispatchService).dispatch("EMAIL", "a@b.com", "CONFIRM", "Hello", "Body");
    }

    @Test
    void history_delegatesToDispatchService() {
        Notification notif = new Notification();
        when(dispatchService.history("a@b.com", "EMAIL")).thenReturn(List.of(notif));

        assertThat(controller.history("a@b.com", "EMAIL")).hasSize(1);
    }
}