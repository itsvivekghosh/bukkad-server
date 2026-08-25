package com.bhukkad.compliance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConsentServiceTest {

    @Mock
    private ConsentRecordRepository consentRecordRepository;

    private ConsentService service;

    @BeforeEach
    void setUp() {
        service = new ConsentService(consentRecordRepository);
    }

    @Test
    void getConsents_returnsUserConsents() {
        ConsentRecord c1 = new ConsentRecord();
        c1.setPurpose("MARKETING");
        when(consentRecordRepository.findByUserId(5L)).thenReturn(List.of(c1));

        assertEquals(1, service.getConsents(5L).size());
        assertEquals("MARKETING", service.getConsents(5L).get(0).getPurpose());
    }

    @Test
    void setConsent_createsNewRecordWhenMissing() {
        when(consentRecordRepository.findByUserIdAndPurpose(5L, "ANALYTICS")).thenReturn(Optional.empty());
        when(consentRecordRepository.save(any(ConsentRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        ConsentRecord result = service.setConsent(5L, "ANALYTICS", true, "web");

        assertEquals(5L, result.getUserId());
        assertEquals("ANALYTICS", result.getPurpose());
        assertTrue(result.getGranted());
        assertEquals("web", result.getSource());
    }

    @Test
    void setConsent_updatesExistingRecord() {
        ConsentRecord existing = new ConsentRecord();
        existing.setUserId(5L);
        existing.setPurpose("MARKETING");
        existing.setGranted(false);
        when(consentRecordRepository.findByUserIdAndPurpose(5L, "MARKETING")).thenReturn(Optional.of(existing));
        when(consentRecordRepository.save(any(ConsentRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        ConsentRecord result = service.setConsent(5L, "MARKETING", true, "email");

        assertTrue(result.getGranted());
        assertEquals("email", result.getSource());
        verify(consentRecordRepository).save(existing);
    }

    @Test
    void setConsent_withNullSource_preservesExistingSource() {
        ConsentRecord existing = new ConsentRecord();
        existing.setUserId(5L);
        existing.setPurpose("MARKETING");
        existing.setGranted(true);
        existing.setSource("original");
        when(consentRecordRepository.findByUserIdAndPurpose(5L, "MARKETING")).thenReturn(Optional.of(existing));
        when(consentRecordRepository.save(any(ConsentRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        ConsentRecord result = service.setConsent(5L, "MARKETING", false, null);

        assertFalse(result.getGranted());
        assertEquals("original", result.getSource());
    }

    @Test
    void allConsented_returnsTrueWhenGranted() {
        ConsentRecord c = new ConsentRecord();
        c.setGranted(true);
        when(consentRecordRepository.findByUserIdAndPurpose(5L, "DATA")).thenReturn(Optional.of(c));

        assertTrue(service.allConsented(5L, "DATA"));
    }

    @Test
    void allConsented_returnsFalseWhenNotGrantedOrMissing() {
        ConsentRecord c = new ConsentRecord();
        c.setGranted(false);
        when(consentRecordRepository.findByUserIdAndPurpose(5L, "DATA")).thenReturn(Optional.of(c));

        assertFalse(service.allConsented(5L, "DATA"));

        when(consentRecordRepository.findByUserIdAndPurpose(6L, "DATA")).thenReturn(Optional.empty());
        assertFalse(service.allConsented(6L, "DATA"));
    }

    @Test
    void revokeAllConsents_revokesOnlyGranted() {
        ConsentRecord granted = new ConsentRecord();
        granted.setGranted(true);
        ConsentRecord denied = new ConsentRecord();
        denied.setGranted(false);
        when(consentRecordRepository.findByUserId(5L)).thenReturn(List.of(granted, denied));

        service.revokeAllConsents(5L);

        assertFalse(granted.getGranted());
        assertEquals("erasure", granted.getSource());
        // only the granted one is persisted
        verify(consentRecordRepository, times(1)).save(any(ConsentRecord.class));
    }

    @Test
    void revokeAllConsents_noConsents_noop() {
        when(consentRecordRepository.findByUserId(5L)).thenReturn(List.of());

        service.revokeAllConsents(5L);

        verify(consentRecordRepository, never()).save(any());
    }
}