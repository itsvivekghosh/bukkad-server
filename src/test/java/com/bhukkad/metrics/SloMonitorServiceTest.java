package com.bhukkad.metrics;

import com.bhukkad.logging.alert.AlertService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SloMonitorServiceTest {

    @Mock
    private AlertService alertService;

    private SloProperties props;
    private MeterRegistry registry;
    private SloMonitorService service;

    @BeforeEach
    void setUp() {
        props = new SloProperties();
        props.setEnabled(true);
        props.setWindowMinutes(5);
        props.setAvailabilityTargetPercent(99.5);
        props.setBurnRateWarning(1.0);
        props.setBurnRateCritical(2.0);
        props.setLatencyTargetMs(500);

        registry = new SimpleMeterRegistry();
        service = new SloMonitorService(registry, alertService, props);
    }

    @Test
    void normalCase_doesNotAlert() {
        Timer successTimer = Timer.builder("bhukkad.http.request")
                .tag("uri", "/api/orders")
                .tag("outcome", "SUCCESS")
                .tag("method", "POST")
                .publishPercentileHistogram()
                .register(registry);
        successTimer.record(Duration.ofMillis(100));

        service.monitor();
        service.monitor();

        verifyNoInteractions(alertService);
    }

    @Test
    void criticalBurnRate_triggersAlertException() {
        Timer successTimer = Timer.builder("bhukkad.http.request")
                .tag("uri", "/api/orders")
                .tag("outcome", "SUCCESS")
                .tag("method", "POST")
                .publishPercentileHistogram()
                .register(registry);
        Timer errorTimer = Timer.builder("bhukkad.http.request")
                .tag("uri", "/api/orders")
                .tag("outcome", "SERVER_ERROR")
                .tag("method", "POST")
                .publishPercentileHistogram()
                .register(registry);

        for (int i = 0; i < 100; i++) {
            successTimer.record(Duration.ofMillis(100));
        }
        service.monitor();

        for (int i = 0; i < 2; i++) {
            errorTimer.record(Duration.ofMillis(200));
        }
        for (int i = 0; i < 98; i++) {
            successTimer.record(Duration.ofMillis(100));
        }
        service.monitor();

        verify(alertService).alertException(anyString(), anyString(), any());
    }

    @Test
    void disabled_doesNotAlert() {
        props.setEnabled(false);

        Timer errorTimer = Timer.builder("bhukkad.http.request")
                .tag("uri", "/api/orders")
                .tag("outcome", "SERVER_ERROR")
                .tag("method", "POST")
                .publishPercentileHistogram()
                .register(registry);
        errorTimer.record(Duration.ofMillis(500));

        service.monitor();
        service.monitor();

        verifyNoInteractions(alertService);
    }
}