package com.bhukkad.fraud;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.FraudEvent;
import com.bhukkad.exception.FraudBlockedException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.FraudEventRepository;
import com.bhukkad.common.web.RequestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    // ---------------------------------------------------------------------
    // Signal resolution: the 2-arg overload and normalization edge cases
    // ---------------------------------------------------------------------

    @Test
    void testCheckAndBlock_TwoArgOverload_ResolvesSignalsFromBoundRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        request.addHeader("X-Device-Fingerprint", "  device-from-header  ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                    eq("AUTH_REGISTER"), eq("203.0.113.7"), any(LocalDateTime.class))).thenReturn(1L);
            when(fraudEventRepository.countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                    eq("AUTH_REGISTER"), eq("device-from-header"), any(LocalDateTime.class))).thenReturn(1L);

            assertDoesNotThrow(() -> fraudDetectionService.checkAndBlock(null, "AUTH_REGISTER"));

            ArgumentCaptor<FraudEvent> savedEvent = ArgumentCaptor.forClass(FraudEvent.class);
            verify(fraudEventRepository).save(savedEvent.capture());
            assertEquals("203.0.113.7", savedEvent.getValue().getIpAddress(),
                    "left-most XFF entry must be attributed");
            assertEquals("device-from-header", savedEvent.getValue().getDeviceFingerprint(),
                    "header fingerprint must be trimmed before persistence");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void testCheckAndBlock_NoRequestContext_PersistsUnknownIpWithoutCounting() {
        RequestContextHolder.resetRequestAttributes();

        assertDoesNotThrow(() -> fraudDetectionService.checkAndBlock(null, "AUTH_REGISTER"));

        ArgumentCaptor<FraudEvent> savedEvent = ArgumentCaptor.forClass(FraudEvent.class);
        verify(fraudEventRepository).save(savedEvent.capture());
        assertEquals(RequestUtils.UNKNOWN_IP, savedEvent.getValue().getIpAddress());
        assertNull(savedEvent.getValue().getDeviceFingerprint());
        verify(fraudEventRepository, never()).countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any());
        verify(fraudEventRepository, never()).countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                anyString(), anyString(), any());
    }

    @Test
    void testCheckAndBlock_BlankIp_NormalizedToUnknownIpAndNotCounted() {
        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(null, "AUTH_REGISTER", null, ""));

        ArgumentCaptor<FraudEvent> savedEvent = ArgumentCaptor.forClass(FraudEvent.class);
        verify(fraudEventRepository).save(savedEvent.capture());
        assertEquals(RequestUtils.UNKNOWN_IP, savedEvent.getValue().getIpAddress());
        verify(fraudEventRepository, never()).countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any());
    }

    @Test
    void testCheckAndBlock_BlankFingerprint_TreatedAsMissingAndNotCounted() {
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(4L);

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(null, "AUTH_REGISTER", "   ", "192.168.1.1"));

        ArgumentCaptor<FraudEvent> savedEvent = ArgumentCaptor.forClass(FraudEvent.class);
        verify(fraudEventRepository).save(savedEvent.capture());
        assertNull(savedEvent.getValue().getDeviceFingerprint());
        verify(fraudEventRepository, never()).countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                anyString(), anyString(), any());
    }

    // ---------------------------------------------------------------------
    // Threshold semantics: disabled dimensions, defaults, boundaries
    // ---------------------------------------------------------------------

    @Test
    void testCheckAndBlock_ZeroPerIpThreshold_BlocksOnDeviceDimensionAlone() {
        FraudProperties props = new FraudProperties();
        props.setEnabled(true);
        props.setBlockingEnabled(true);
        props.setWindowMinutes(60);
        props.setRetryAfterSeconds(300);
        props.setThresholds(Map.of("AUTH_LOGIN", new FraudProperties.Threshold(0, 3)));
        ReflectionTestUtils.setField(fraudDetectionService, "fraudProperties", props);

        when(fraudEventRepository.countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                eq("AUTH_LOGIN"), anyString(), any(LocalDateTime.class))).thenReturn(3L);

        FraudBlockedException ex = assertThrows(FraudBlockedException.class, () ->
                fraudDetectionService.checkAndBlock(null, "AUTH_LOGIN", "device-y", "unknown"));

        assertEquals("AUTH_LOGIN", ex.getEventType());
        assertEquals(300, ex.getRetryAfterSeconds());
        verify(fraudEventRepository, never()).countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any());
    }

    @Test
    void testCheckAndBlock_AllThresholdsZero_NeverBlocksRegardlessOfCounts() {
        FraudProperties props = new FraudProperties();
        props.setEnabled(true);
        props.setBlockingEnabled(true);
        props.setWindowMinutes(60);
        props.setRetryAfterSeconds(300);
        props.setThresholds(Map.of("ORDER_CREATE", new FraudProperties.Threshold(0, 0)));
        ReflectionTestUtils.setField(fraudDetectionService, "fraudProperties", props);

        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                eq("ORDER_CREATE"), anyString(), any(LocalDateTime.class))).thenReturn(999L);
        when(fraudEventRepository.countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                eq("ORDER_CREATE"), anyString(), any(LocalDateTime.class))).thenReturn(999L);

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(null, "ORDER_CREATE", "device-q", "192.168.1.9"));
    }

    @Test
    void testCheckAndBlock_UnlistedEventType_FallsBackToDefaultThresholdBoundary() {
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                eq(FraudEventTypes.PROMO_REDEMPTION), anyString(), any(LocalDateTime.class)))
                .thenReturn(19L, 20L);

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(
                        null, FraudEventTypes.PROMO_REDEMPTION, null, "192.168.1.20"));

        FraudBlockedException ex = assertThrows(FraudBlockedException.class, () ->
                fraudDetectionService.checkAndBlock(
                        null, FraudEventTypes.PROMO_REDEMPTION, null, "192.168.1.20"));

        assertEquals(FraudEventTypes.PROMO_REDEMPTION, ex.getEventType());
    }

    @Test
    void testCheckAndBlock_EventTypeMissingFromCustomMap_UsesBuiltInDefaultThresholds() {
        FraudProperties props = new FraudProperties();
        props.setEnabled(true);
        props.setBlockingEnabled(true);
        props.setWindowMinutes(60);
        props.setRetryAfterSeconds(300);
        props.setThresholds(Map.of("AUTH_REGISTER", new FraudProperties.Threshold(3, 3)));
        ReflectionTestUtils.setField(fraudDetectionService, "fraudProperties", props);

        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                eq(FraudEventTypes.AUTH_LOGIN), anyString(), any(LocalDateTime.class)))
                .thenReturn(30L, 40L);

        // Built-in AUTH_LOGIN per-ip threshold is 40: 30 passes where a naive custom-map lookup
        // would have used nothing at all, and 40 trips it exactly.
        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(
                        null, FraudEventTypes.AUTH_LOGIN, null, "192.168.1.21"));
        assertThrows(FraudBlockedException.class, () ->
                fraudDetectionService.checkAndBlock(
                        null, FraudEventTypes.AUTH_LOGIN, null, "192.168.1.21"));
    }

    @Test
    void testCheckAndBlock_SaveFails_FailsOpenWithoutCounting() {
        doThrow(new RuntimeException("fraud store unreachable"))
                .when(fraudEventRepository).save(any(FraudEvent.class));

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(1L, "AUTH_REGISTER", "device123", "192.168.1.1"));

        verify(fraudEventRepository, never()).countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any());
        verify(fraudEventRepository, never()).countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                anyString(), anyString(), any());
    }

    @Test
    void testCheckAndBlock_CountWindow_MatchesConfiguredWindowMinutes() {
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                eq("ORDER_CREATE"), anyString(), any())).thenReturn(0L);
        when(fraudEventRepository.countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                eq("ORDER_CREATE"), anyString(), any())).thenReturn(0L);

        fraudDetectionService.checkAndBlock(null, "ORDER_CREATE", "device-w", "192.168.1.50");

        ArgumentCaptor<LocalDateTime> ipSince = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> deviceSince = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fraudEventRepository).countByEventTypeAndIpAddressAndCreatedAtAfter(
                eq("ORDER_CREATE"), eq("192.168.1.50"), ipSince.capture());
        verify(fraudEventRepository).countByEventTypeAndDeviceFingerprintAndCreatedAtAfter(
                eq("ORDER_CREATE"), eq("device-w"), deviceSince.capture());

        LocalDateTime lowerBound = LocalDateTime.now().minusMinutes(61);
        LocalDateTime upperBound = LocalDateTime.now().minusMinutes(59);
        for (LocalDateTime since : List.of(ipSince.getValue(), deviceSince.getValue())) {
            assertFalse(since.isBefore(lowerBound), "window start must be ~windowMinutes before now");
            assertFalse(since.isAfter(upperBound));
        }
    }

    @Test
    void testCheckAndBlock_KnownCustomerId_LinksCustomerOnPersistedEvent() {
        Customer customer = new Customer();
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer));
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0L);

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(7L, "AUTH_REGISTER", null, "192.168.1.30"));

        ArgumentCaptor<FraudEvent> savedEvent = ArgumentCaptor.forClass(FraudEvent.class);
        verify(fraudEventRepository).save(savedEvent.capture());
        assertSame(customer, savedEvent.getValue().getCustomer());
        assertEquals("AUTH_REGISTER", savedEvent.getValue().getEventType());
    }

    @Test
    void testCheckAndBlock_UnknownCustomerId_PersistsAnonymousEvent() {
        when(customerRepository.findById(404L)).thenReturn(Optional.empty());
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0L);

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(404L, "AUTH_REGISTER", null, "192.168.1.31"));

        ArgumentCaptor<FraudEvent> savedEvent = ArgumentCaptor.forClass(FraudEvent.class);
        verify(fraudEventRepository).save(savedEvent.capture());
        assertNull(savedEvent.getValue().getCustomer());
    }

    @Test
    void testCheckAndBlock_AnonymousCaller_DoesNotTouchCustomerRepository() {
        when(fraudEventRepository.countByEventTypeAndIpAddressAndCreatedAtAfter(
                anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0L);

        assertDoesNotThrow(() ->
                fraudDetectionService.checkAndBlock(null, "AUTH_REGISTER", null, "192.168.1.32"));

        verifyNoInteractions(customerRepository);
    }

    // ---------------------------------------------------------------------
    // Legacy boolean signal: checkAndLog (fixed threshold of 5 over one hour)
    // ---------------------------------------------------------------------

    @Test
    void testCheckAndLog_IpCountReachesFixedThreshold_ReportsSuspicious() {
        when(fraudEventRepository.countByDeviceFingerprintAndCreatedAtAfter(
                anyString(), any(LocalDateTime.class))).thenReturn(1L);
        when(fraudEventRepository.countByIpAddressAndCreatedAtAfter(
                anyString(), any(LocalDateTime.class))).thenReturn(5L);

        assertTrue(fraudDetectionService.checkAndLog(null, "AUTH_LOGIN", "device-l", "192.168.2.10"));
    }

    @Test
    void testCheckAndLog_DeviceCountReachesFixedThreshold_ReportsSuspicious() {
        when(fraudEventRepository.countByDeviceFingerprintAndCreatedAtAfter(
                anyString(), any(LocalDateTime.class))).thenReturn(6L);
        when(fraudEventRepository.countByIpAddressAndCreatedAtAfter(
                anyString(), any(LocalDateTime.class))).thenReturn(2L);

        assertTrue(fraudDetectionService.checkAndLog(null, "AUTH_LOGIN", "device-l", "192.168.2.11"));
    }

    @Test
    void testCheckAndLog_BelowBothFixedThresholds_ReportsBenign() {
        when(fraudEventRepository.countByDeviceFingerprintAndCreatedAtAfter(
                anyString(), any(LocalDateTime.class))).thenReturn(2L);
        when(fraudEventRepository.countByIpAddressAndCreatedAtAfter(
                anyString(), any(LocalDateTime.class))).thenReturn(3L);

        assertFalse(fraudDetectionService.checkAndLog(null, "ORDER_CREATE", "device-m", "192.168.2.12"));
    }

    @Test
    void testCheckAndLog_BlankSignals_SkipsCountingAndPersistsRawValues() {
        boolean suspicious =
                fraudDetectionService.checkAndLog(null, "AUTH_LOGIN", "   ", "");

        assertFalse(suspicious);
        verify(fraudEventRepository, never()).countByDeviceFingerprintAndCreatedAtAfter(
                anyString(), any());
        verify(fraudEventRepository, never()).countByIpAddressAndCreatedAtAfter(
                anyString(), any());

        ArgumentCaptor<FraudEvent> savedEvent = ArgumentCaptor.forClass(FraudEvent.class);
        verify(fraudEventRepository).save(savedEvent.capture());
        assertNull(savedEvent.getValue().getDeviceFingerprint(),
                "blank fingerprint must not be persisted");
        assertEquals("", savedEvent.getValue().getIpAddress(),
                "the legacy path persists the raw ip value as supplied");
    }

    @Test
    void testCheckAndLog_CountsRawFingerprintButPersistsNormalizedValue() {
        when(fraudEventRepository.countByDeviceFingerprintAndCreatedAtAfter(
                eq("device-with-padding "), any(LocalDateTime.class))).thenReturn(0L);
        when(fraudEventRepository.countByIpAddressAndCreatedAtAfter(
                eq("192.168.2.44"), any(LocalDateTime.class))).thenReturn(0L);

        assertFalse(fraudDetectionService.checkAndLog(
                null, "AUTH_LOGIN", "device-with-padding ", "192.168.2.44"));

        verify(fraudEventRepository).countByDeviceFingerprintAndCreatedAtAfter(
                eq("device-with-padding "), any(LocalDateTime.class));

        ArgumentCaptor<FraudEvent> savedEvent = ArgumentCaptor.forClass(FraudEvent.class);
        verify(fraudEventRepository).save(savedEvent.capture());
        assertEquals("device-with-padding", savedEvent.getValue().getDeviceFingerprint(),
                "persistence must receive the normalized fingerprint");
    }
}
