package com.bhukkad.notification.api.controller;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.notification.domain.entity.Notification;
import com.bhukkad.notification.domain.service.impl.NotificationDispatchService;
import com.bhukkad.notification.api.dto.request.DispatchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock private NotificationDispatchService dispatchService;
    @InjectMocks private NotificationController controller;

    private TokenPrincipal admin() {
        return new TokenPrincipal(1L, "admin@example.com", "ADMIN");
    }

    @Test
    void dispatch_delegatesToDispatchService() {
        Notification notification = new Notification();
        notification.setId(1L);
        notification.setStatus(Notification.STATUS_SENT);
        when(dispatchService.dispatch("EMAIL", "a@b.com", "CONFIRM", "Hello", "Body"))
                .thenReturn(notification);

        DispatchRequest request =
                new DispatchRequest("EMAIL", "a@b.com", "CONFIRM", "Hello", "Body");

        assertThat(controller.dispatch(request).getBody().getStatus()).isEqualTo(Notification.STATUS_SENT);
        verify(dispatchService).dispatch("EMAIL", "a@b.com", "CONFIRM", "Hello", "Body");
    }

    @Test
    void dispatch_declaresAdminAndRateLimitGates() throws Exception {
        // Method-security and rate limiting are enforced by AOP in the full
        // context; the unit matrix pins the annotations (dispatch is the only
        // unbounded send primitive — ADMIN gate + 60/min per actor).
        var m = NotificationController.class.getMethod("dispatch",
                DispatchRequest.class);
        var authz = m.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        var rate = m.getAnnotation(com.bhukkad.common.ratelimit.RateLimited.class);
        assertThat(authz).isNotNull();
        assertThat(authz.value()).contains("ADMIN");
        assertThat(rate).isNotNull();
        assertThat(rate.bucket()).isEqualTo("notification-dispatch");
        assertThat(rate.limit()).isPositive();
    }

    @Test
    void history_delegatesToDispatchService() {
        Notification notif = new Notification();
        when(dispatchService.history("a@b.com", "EMAIL")).thenReturn(List.of(notif));

        assertThat(controller.history("a@b.com", "EMAIL")).hasSize(1);
    }
}
