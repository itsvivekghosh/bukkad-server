package com.bhukkad.admin.audit;
import com.bhukkad.admin.domain.service.AuditService;

import com.bhukkad.admin.domain.entity.AuditEvent;
import com.bhukkad.admin.domain.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditEventRepository auditEventRepository;

    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(auditEventRepository);
    }

    @Test
    void record_savesEventWithNullActor() {
        service.record("REFUND", "PAYMENT", "123", "old", "new");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.getAction()).isEqualTo("REFUND");
        assertThat(event.getEntityType()).isEqualTo("PAYMENT");
        assertThat(event.getEntityId()).isEqualTo(123L);
        assertThat(event.getOldState()).isEqualTo("old");
        assertThat(event.getNewState()).isEqualTo("new");
        assertThat(event.getActorId()).isNull();
        assertThat(event.getActorRole()).isNull();
    }

    @Test
    void recordEvent_withExplicitActor_savesActorId() {
        service.recordEvent("DEACTIVATE", "USER", "55", "active", "inactive", 55L);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.getActorId()).isEqualTo(55L);
        assertThat(event.getAction()).isEqualTo("DEACTIVATE");
        assertThat(event.getEntityType()).isEqualTo("USER");
        assertThat(event.getEntityId()).isEqualTo(55L);
        assertThat(event.getOldState()).isEqualTo("active");
        assertThat(event.getNewState()).isEqualTo("inactive");
    }

    @Test
    void recordEvent_withNullActor_savesNullActor() {
        service.recordEvent("LOGIN_FAILED", "AUTH", null, null, null, null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.getActorId()).isNull();
        assertThat(event.getActorRole()).isNull();
        assertThat(event.getAction()).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void repositoryException_swallowedDoesNotPropagate() {
        doThrow(new RuntimeException("DB error")).when(auditEventRepository).save(any());

        assertThatCode(() -> service.record("TEST", "TEST", "1", null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void record_populatesIpAddressAndTraceIds() {
        service.record("ACTION", "TYPE", "1", null, null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.getIpAddress()).isNotNull();
    }

    @Test
    void record_withNullOldAndNewState() {
        service.record("LOGIN", "AUTH", "1", null, null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.getOldState()).isNull();
        assertThat(event.getNewState()).isNull();
        assertThat(event.getAction()).isEqualTo("LOGIN");
    }
}
