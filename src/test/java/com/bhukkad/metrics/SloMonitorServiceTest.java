package com.bhukkad.metrics;

import com.bhukkad.logging.alert.AlertService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

    private Timer registerRequestTimer(String uri) {
        return Timer.builder("bhukkad.http.requests")
                .tag("uri", uri)
                .tag("method", "POST")
                .publishPercentileHistogram()
                .register(registry);
    }

    private Counter registerErrorCounter() {
        return Counter.builder("bhukkad.http.errors")
                .tag("uri", "/api/orders")
                .tag("method", "POST")
                .register(registry);
    }

    @Test
    void normalCase_doesNotAlert() {
        Timer successTimer = registerRequestTimer("/api/orders");
        for (int i = 0; i < 100; i++) {
            successTimer.record(Duration.ofMillis(100));
        }
        service.monitor();
        for (int i = 0; i < 100; i++) {
            successTimer.record(Duration.ofMillis(100));
        }
        service.monitor();

        verifyNoInteractions(alertService);
    }

    @Test
    void criticalBurnRate_triggersAlertException() {
        Timer successTimer = registerRequestTimer("/api/orders");
        Counter errorCounter = registerErrorCounter();

        for (int i = 0; i < 100; i++) {
            successTimer.record(Duration.ofMillis(100));
        }
        service.monitor();

        errorCounter.increment();
        errorCounter.increment();
        for (int i = 0; i < 98; i++) {
            successTimer.record(Duration.ofMillis(100));
        }
        service.monitor();

        // 2 errors / 100 requests = 2% error rate vs 0.5% allowed → burn rate 4 ≥ critical 2.
        verify(alertService).alertException(anyString(), anyString(), any());
    }

    @Test
    void latencyBreach_p95ExceedsTarget_alertFires() {
        MeterRegistry mockRegistry = mock(MeterRegistry.class);
        Search search = mock(Search.class);
        Timer timer = mock(Timer.class);
        HistogramSnapshot snapshot = new HistogramSnapshot(100, 100_000, 1_000,
                new ValueAtPercentile[]{new ValueAtPercentile(0.95, 1_000_000_000.0)},
                new CountAtBucket[0], null);
        when(mockRegistry.find("bhukkad.http.requests")).thenReturn(search);
        when(search.timers()).thenReturn(List.of(timer));
        Search errorSearch = mock(Search.class);
        when(mockRegistry.find("bhukkad.http.errors")).thenReturn(errorSearch);
        when(errorSearch.counters()).thenReturn(List.of());
        when(timer.takeSnapshot()).thenReturn(snapshot);

        SloMonitorService svc = new SloMonitorService(mockRegistry, alertService, props);
        when(timer.count()).thenReturn(100L);
        svc.monitor();
        when(timer.count()).thenReturn(200L);
        svc.monitor();

        // p95 (1.0s = 1000ms) exceeds latency target (500ms) → WARNING alert.
        verify(alertService).alert(
                any(), any(),
                org.mockito.ArgumentMatchers.eq("SLO latency target breached"),
                any());
    }

    @Test
    void computeMaxP95Ms_returnsMaxAcrossTimers() {
        Timer fast = mock(Timer.class);
        HistogramSnapshot fastSnap = new HistogramSnapshot(1, 10, 10,
                new ValueAtPercentile[]{new ValueAtPercentile(0.95, 100_000_000.0)},
                new CountAtBucket[0], null);
        when(fast.takeSnapshot()).thenReturn(fastSnap);

        Timer slow = mock(Timer.class);
        HistogramSnapshot slowSnap = new HistogramSnapshot(1, 1000, 1000,
                new ValueAtPercentile[]{new ValueAtPercentile(0.95, 2_000_000_000.0)},
                new CountAtBucket[0], null);
        when(slow.takeSnapshot()).thenReturn(slowSnap);

        double p95 = SloMonitorService.computeMaxP95Ms(List.of(fast, slow));

        assertTrue(p95 == 2000.0, "expected 2000ms, got " + p95);
    }

    @Test
    void computeMaxP95Ms_timerWithoutPercentiles_returnsZero() {
        Timer bare = mock(Timer.class);
        HistogramSnapshot snap = new HistogramSnapshot(1, 1, 1,
                new ValueAtPercentile[0],
                new CountAtBucket[0], null);
        when(bare.takeSnapshot()).thenReturn(snap);

        assertEquals(0.0, SloMonitorService.computeMaxP95Ms(List.of(bare)));
    }

    @Test
    void computeMaxP95Ms_emptyTimers_returnsZero() {
        assertEquals(0.0, SloMonitorService.computeMaxP95Ms(List.of()));
    }

    @Test
    void insufficientWindowData_noAlert() {
        Timer successTimer = registerRequestTimer("/api/orders");
        successTimer.record(Duration.ofMillis(100));

        service.monitor();

        verifyNoInteractions(alertService);
    }

    @Test
    void noRequestsInWindow_noAlert() {
        Timer successTimer = registerRequestTimer("/api/orders");
        successTimer.record(Duration.ofMillis(100));

        service.monitor();
        service.monitor();

        verifyNoInteractions(alertService);
    }

    @Test
    void disabled_doesNotAlert() {
        props.setEnabled(false);

        Timer errorTimer = registerRequestTimer("/api/orders");
        errorTimer.record(Duration.ofMillis(500));
        registerErrorCounter().increment();

        service.monitor();
        service.monitor();

        verifyNoInteractions(alertService);
    }
}