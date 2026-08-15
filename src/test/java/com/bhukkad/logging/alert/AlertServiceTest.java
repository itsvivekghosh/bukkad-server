package com.bhukkad.logging.alert;

import com.bhukkad.config.AlertingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock
    private WebhookAlertNotifier webhookAlertNotifier;

    private AlertService alertService;

    @BeforeEach
    void setUp() {
        AlertingProperties properties = new AlertingProperties();
        properties.setEnabled(true);
        properties.getSlowRequest().setWarningThresholdMs(1000);
        properties.getSlowRequest().setCriticalThresholdMs(3000);
        alertService = new AlertService(properties, new ObjectMapper(), webhookAlertNotifier);
        MDC.clear();
    }

    @Test
    void alertSlowRequest_firesForCriticalDuration() {
        alertService.alertSlowRequest("POST", "/api/orders", 3500, 200);

        verify(webhookAlertNotifier, atLeastOnce()).sendIfEnabled(
                eq(AlertSeverity.CRITICAL),
                eq(AlertCategory.SLOW_REQUEST),
                eq("Slow request detected"),
                any());
    }

    @Test
    void alertSlowRequest_skipsFastRequests() {
        alertService.alertSlowRequest("GET", "/api/health/ping", 50, 200);

        verify(webhookAlertNotifier, never()).sendIfEnabled(any(), any(), any(), any());
    }

    @Test
    void alertHttpError_firesForServerErrors() {
        alertService.alertHttpError("GET", "/api/fail", 500, 120);

        verify(webhookAlertNotifier, atLeastOnce()).sendIfEnabled(
                eq(AlertSeverity.CRITICAL),
                eq(AlertCategory.HTTP_ERROR),
                eq("Server error response"),
                any());
    }
}
