package com.bhukkad.notification.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.notification.domain.entity.Notification;
import com.bhukkad.notification.domain.repository.NotificationRepository;
import com.bhukkad.notification.domain.service.NotificationChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock private NotificationRepository repository;
    @Mock private NotificationChannel channel;
    @InjectMocks private NotificationDispatchService service;

    @Test
    void dispatch_email_markedSent() {
        when(channel.supports("EMAIL")).thenReturn(true);
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification result = service.dispatch("EMAIL", "a@b.com", "ORDER_CONFIRMED", "Hi", "Body");

        assertThat(result.getStatus()).isEqualTo(Notification.STATUS_SENT);
        assertThat(result.getProviderRef()).startsWith("NOTIF-");
        verify(channel).send(any(Notification.class));
        verify(repository, org.mockito.Mockito.atLeast(1)).save(any(Notification.class));
    }

    @Test
    void dispatch_unsupportedChannel_throws() {
        when(channel.supports("FAX")).thenReturn(false);
        assertThatThrownBy(() -> service.dispatch("FAX", "a@b.com", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported channel");
    }

    @Test
    void history_delegatesToRepository() {
        service.history("a@b.com", "EMAIL");
        verify(repository).findByRecipientAndChannel("a@b.com", "EMAIL");
    }
}
