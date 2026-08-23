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
        when(alertingProperties.getSlowRequest()).thenReturn(new AlertingProperties.SlowRequest());
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
    void alert_deduplicatesRepeatedAlerts() throws JsonProcessingException {
        doReturn("{}").when(objectMapper).writeValueAsString(anyMap());
        service.alert(AlertSeverity.WARNING, AlertCategory.EXCEPTION, "duplicate");
        service.alert(AlertSeverity.WARNING, AlertCategory.EXCEPTION, "duplicate");
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
}