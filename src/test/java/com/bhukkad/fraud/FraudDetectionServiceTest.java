package com.bhukkad.fraud;

import com.bhukkad.entity.FraudEvent;
import com.bhukkad.exception.FraudBlockedException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.FraudEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    @Mock
    private FraudEventRepository fraudEventRepository;
    @Mock
    private CustomerRepository customerRepository;

    private FraudDetectionService fraudDetectionService;

    @BeforeEach
    void setUp() {
        FraudProperties props = new FraudProperties();
        props.setEnabled(true);
        props.setBlockingEnabled(true);
        props.setWindowMinutes(60);
        props.setRetryAfterSeconds(300);
        props.setThresholds(Map.of(
                "AUTH_REGISTER", new FraudProperties.Threshold(10, 5),
                "AUTH_LOGIN", new FraudProperties.Threshold(40, 25),
                "ORDER_CREATE", new FraudProperties.Threshold(25, 15)
        ));
        fraudDetectionService = new FraudDetectionService(fraudEventRepository, customerRepository, props);
    }

    @Test
    void testCheckAndBlock_NoBlockWhenUnderThreshold() {
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(5L);
        when(fraudEventRepository.countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(3L);

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(null, "AUTH_REGISTER", "device123", "192.168.1.1"));
    }

    @Test
    void testCheckAndBlock_BlocksIpWhenOverThreshold() {
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(10L);
        when(fraudEventRepository.countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(3L);

        FraudBlockedException ex = assertThrows(FraudBlockedException.class, () ->
                fraudDetectionService.checkAndBlock(1L, "AUTH_REGISTER", "device123", "192.168.1.1"));

        assertTrue(ex.getMessage().contains("Unusual activity detected"));
        assertEquals(300, ex.getRetryAfterSeconds());
    }

    @Test
    void testCheckAndBlock_BlocksDeviceWhenOverThreshold() {
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(5L);
        when(fraudEventRepository.countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(5L);

        FraudBlockedException ex = assertThrows(FraudBlockedException.class, () ->
                fraudDetectionService.checkAndBlock(1L, "AUTH_REGISTER", "device123", "192.168.1.1"));

        assertEquals("AUTH_REGISTER", ex.getEventType());
    }

    @Test
    void testCheckAndBlock_ObservationMode_NoBlock() {
        FraudProperties props = new FraudProperties();
        props.setEnabled(true);
        props.setBlockingEnabled(false);
        ReflectionTestUtils.setField(fraudDetectionService, "fraudProperties", props);

        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(10L);

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(null, "AUTH_REGISTER", "device123", "192.168.1.1"));
        verify(fraudEventRepository).save(any(FraudEvent.class));
    }

    @Test
    void testCheckAndBlock_Disabled_NoAction() {
        FraudProperties props = new FraudProperties();
        props.setEnabled(false);
        ReflectionTestUtils.setField(fraudDetectionService, "fraudProperties", props);

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(null, "AUTH_REGISTER", null, "192.168.1.1"));
        verifyNoInteractions(fraudEventRepository);
    }

    @Test
    void testCheckAndBlock_UnknownIp_NotCounted() {
        when(fraudEventRepository.countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(3L);

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(null, "AUTH_REGISTER", "device123", "unknown"));
    }

    @Test
    void testCheckAndBlock_FailOpen_OnError() {
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("DB error"));

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(null, "AUTH_REGISTER", "device123", "192.168.1.1"));
    }

    @Test
    void testCheckAndBlock_PersistEventBeforeCounting() {
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(5L);

        InOrder inOrder = inOrder(fraudEventRepository);
        fraudDetectionService.checkAndBlock(1L, "AUTH_REGISTER", "device123", "192.168.1.1");

        inOrder.verify(fraudEventRepository).save(any(FraudEvent.class));
        inOrder.verify(fraudEventRepository).countByEventTypeAndIpAddressAndCreatedAtAfter(anyString(), anyString(), any());
    }

    @Test
    void testCheckAndBlock_NullFingerprint_Handled() {
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(5L);

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(null, "AUTH_REGISTER", null, "192.168.1.1"));
    }

    @Test
    void testCheckAndBlock_NewTransaction_SavesEvenOnRollback() {
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(10L);

        FraudBlockedException ex = assertThrows(FraudBlockedException.class, () ->
                fraudDetectionService.checkAndBlock(1L, "AUTH_REGISTER", "device123", "192.168.1.1"));

        verify(fraudEventRepository).save(any(FraudEvent.class));
    }
}
