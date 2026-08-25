package com.bhukkad.audit;

import com.bhukkad.entity.User;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditEventRepository auditEventRepository;
    @Mock
    private SecurityUtils securityUtils;

    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(auditEventRepository, securityUtils);
    }

    @Test
    void record_capturesActionAndActor() {
        User user = new User();
        user.setId(42L);
        user.setRole(User.UserRole.CUSTOMER);
        when(securityUtils.getCurrentUser()).thenReturn(user);

        service.record("REFUND", "PAYMENT", "pay-123", "old", "new");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertEquals(42L, event.getActorId());
        assertEquals("CUSTOMER", event.getActorRole());
        assertEquals("REFUND", event.getAction());
        assertEquals("PAYMENT", event.getResourceType());
        assertEquals("pay-123", event.getResourceId());
        assertEquals("old", event.getOldState());
        assertEquals("new", event.getNewState());
        assertNotNull(event.getIpAddress());
        // trace/request ids come from MDC TraceContext (null outside a request thread)
        assertTrue(event.getTraceId() == null || event.getTraceId() instanceof String);
        assertTrue(event.getRequestId() == null || event.getRequestId() instanceof String);
    }

    @Test
    void record_withoutAuth_usesNullActor() {
        when(securityUtils.getCurrentUser()).thenThrow(new RuntimeException("No auth"));

        service.record("LOGIN_FAILED", "AUTH", "email@test.com", null, null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        assertNull(captor.getValue().getActorId());
        assertNull(captor.getValue().getActorRole());
    }

    @Test
    void recordEvent_withExplicitActor_bypassesSecurity() {
        service.recordEvent("DEACTIVATE", "USER", "55", "active", "inactive", 55L);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        assertEquals(55L, captor.getValue().getActorId());
        verify(securityUtils, never()).getCurrentUser();
    }

    @Test
    void recordEvent_withNullActor_fallsBackToSecurity() {
        User user = new User();
        user.setId(10L);
        user.setRole(User.UserRole.ADMIN);
        when(securityUtils.getCurrentUser()).thenReturn(user);

        service.recordEvent("PROMOTE", "USER", "5", null, null, null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        assertEquals(10L, captor.getValue().getActorId());
        assertEquals("ADMIN", captor.getValue().getActorRole());
    }

    @Test
    void repositoryException_swallowedDoesNotPropagate() {
        when(securityUtils.getCurrentUser()).thenThrow(new RuntimeException("No auth"));
        doThrow(new RuntimeException("DB error")).when(auditEventRepository).save(any());

        // Must not throw, even when the repository save fails.
        assertDoesNotThrow(() -> service.record("TEST", "TEST", "1", null, null));
    }

    @Test
    void resolveCurrentActor_returnsNull_whenUserIsNull() {
        when(securityUtils.getCurrentUser()).thenReturn(null);

        service.record("ACTION", "TYPE", "id", null, null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        assertNull(captor.getValue().getActorId());
        assertNull(captor.getValue().getActorRole());
    }

    @Test
    void record_withNullOldAndNewState() {
        User user = new User();
        user.setId(1L);
        user.setRole(User.UserRole.DELIVERY_AGENT);
        when(securityUtils.getCurrentUser()).thenReturn(user);

        service.record("LOGIN", "AUTH", "email@t.com", null, null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        assertNull(captor.getValue().getOldState());
        assertNull(captor.getValue().getNewState());
        assertEquals("LOGIN", captor.getValue().getAction());
    }
}