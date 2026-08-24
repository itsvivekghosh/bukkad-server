package com.bhukkad.logging.alert;

import com.bhukkad.config.AlertingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertServiceTest {

    @Mock
    private AlertingProperties alertingProperties;
    @Mock
    private AlertingProperties.HttpError httpError;
    @Mock
    private AlertingProperties.SlowRequest slowRequest;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private WebhookAlertNotifier webhookAlertNotifier;

    @InjectMocks
    private AlertService service;

    @BeforeEach
    void setUp() {
        when(alertingProperties.isEnabled()).thenReturn(true);
        when(alertingProperties.getHttpError()).thenReturn(httpError);
        when(httpError.isAlertOn5xx()).thenReturn(true);
        when(httpError.isAlertOn4xx()).thenReturn(true);
        when(alertingProperties.getSlowRequest()).thenReturn(slowRequest);
    }

    @Test
    void alert_skipsWhenDisabled() throws JsonProcessingException {
        when(alertingProperties.isEnabled()).thenReturn(false);
        service.alert(AlertSeverity.WARNING, AlertCategory.EXCEPTION, "test");
        verifyNoInteractions(objectMapper);
    }

    @Test
    void alert_logsWarning() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alert(AlertSeverity.WARNING, AlertCategory.EXCEPTION, "test");
    }

    @Test
    void alert_logsCritical() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alert(AlertSeverity.CRITICAL, AlertCategory.EXCEPTION, "critical event");
    }

    @Test
    void alert_logsInfo() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alert(AlertSeverity.INFO, AlertCategory.EXCEPTION, "info event");
    }

    @Test
    void alert_deduplicatesRepeatedAlerts() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alert(AlertSeverity.WARNING, AlertCategory.EXCEPTION, "duplicate");
        service.alert(AlertSeverity.WARNING, AlertCategory.EXCEPTION, "duplicate");
    }

    @Test
    void alert_serializationFailure_fallsBackToLog() throws JsonProcessingException {
        when(objectMapper.writeValueAsString(anyMap())).thenThrow(new RuntimeException("serialization failed"));
        assertDoesNotThrow(() -> service.alert(AlertSeverity.WARNING, AlertCategory.EXCEPTION, "test"));
    }

    @Test
    void alert_withContextMap() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alert(AlertSeverity.CRITICAL, AlertCategory.PAYMENT, "payment failed", Map.of("orderId", "123"));
        verify(webhookAlertNotifier).sendIfEnabled(any(), any(), anyString(), anyMap());
    }

    @Test
    void alertHttpError_delegatesToWebhook() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alertHttpError("GET", "/api/test", 500, 100);
        verify(webhookAlertNotifier).sendIfEnabled(
                any(AlertSeverity.class),
                any(AlertCategory.class),
                anyString(),
                any(Map.class)
        );
    }

    @Test
    void alertHttpError_5xx_alertOn5xxDisabled_skips() {
        when(httpError.isAlertOn5xx()).thenReturn(false);
        service.alertHttpError("GET", "/api/test", 500, 100);
        verifyNoInteractions(webhookAlertNotifier);
    }

    @Test
    void alertHttpError_4xx_securityCategory() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alertHttpError("POST", "/login", 401, 50);
        verify(webhookAlertNotifier).sendIfEnabled(any(), any(), anyString(), anyMap());
    }

    @Test
    void alertHttpError_4xx_infoCategory() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alertHttpError("GET", "/api/items", 400, 30);
        verify(webhookAlertNotifier).sendIfEnabled(any(), any(), anyString(), anyMap());
    }

    @Test
    void alertHttpError_4xx_alertOn4xxDisabled_skips() {
        when(httpError.isAlertOn4xx()).thenReturn(false);
        service.alertHttpError("GET", "/api/test", 404, 100);
        verifyNoInteractions(webhookAlertNotifier);
    }

    @Test
    void alertHttpError_3xx_noAlert() {
        service.alertHttpError("GET", "/redirect", 302, 10);
        verifyNoInteractions(webhookAlertNotifier);
    }

    @Test
    void alertSlowRequest_belowWarning_skips() {
        when(slowRequest.getWarningThresholdMs()).thenReturn(1000L);
        when(slowRequest.getCriticalThresholdMs()).thenReturn(3000L);
        service.alertSlowRequest("GET", "/api", 500, 200);
        verifyNoInteractions(webhookAlertNotifier);
    }

    @Test
    void alertSlowRequest_warning() throws JsonProcessingException {
        when(slowRequest.getWarningThresholdMs()).thenReturn(1000L);
        when(slowRequest.getCriticalThresholdMs()).thenReturn(3000L);
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alertSlowRequest("GET", "/api", 1500, 200);
        verify(webhookAlertNotifier).sendIfEnabled(any(), any(), anyString(), anyMap());
    }

    @Test
    void alertSlowRequest_critical() throws JsonProcessingException {
        when(slowRequest.getWarningThresholdMs()).thenReturn(1000L);
        when(slowRequest.getCriticalThresholdMs()).thenReturn(3000L);
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alertSlowRequest("POST", "/api/order", 5000, 200);
        verify(webhookAlertNotifier).sendIfEnabled(any(), any(), anyString(), anyMap());
    }

    @Test
    void alertException_includesSourceAndType() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alertException("MyService", "Something broke", new NullPointerException("npe"));
        verify(webhookAlertNotifier).sendIfEnabled(any(), any(), anyString(), anyMap());
    }

    @Test
    void alertException_withNullThrowable() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alertException("MyService", "Something broke", null);
        verify(webhookAlertNotifier).sendIfEnabled(any(), any(), anyString(), anyMap());
    }

    @Test
    void alert_overridesDedupAfterWindow() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        when(alertingProperties.getDedupWindowSeconds()).thenReturn(0L);
        service.alert(AlertSeverity.WARNING, AlertCategory.EXCEPTION, "dedup-test");
        service.alert(AlertSeverity.WARNING, AlertCategory.EXCEPTION, "dedup-test");
    }
}