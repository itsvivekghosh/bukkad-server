package com.bhukkad.metrics;

import com.bhukkad.live.OrderLiveReplayStore;
import com.bhukkad.live.OrderSseStreamService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformMetricsTest {

    private OrderSseStreamService sseService() {
        OrderLiveReplayStore replayStore = mock(OrderLiveReplayStore.class);
        when(replayStore.replayAfter(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Collections.emptyList());
        return new OrderSseStreamService(replayStore);
    }

    @Test void registersSseGaugeWithZeroConnections() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new PlatformMetrics(registry, sseService());

        Gauge gauge = registry.get("sse_active_connections").gauge();
        assertEquals(0.0, gauge.value());
    }

    @Test void gaugeTracksConnectionCount() {
        MeterRegistry registry = new SimpleMeterRegistry();
        OrderSseStreamService sse = sseService();
        sse.subscribeKitchen(1L, null);
        sse.subscribeKitchen(1L, null);
        sse.subscribeRider(5L, null);
        new PlatformMetrics(registry, sse);

        Gauge gauge = registry.get("sse_active_connections").gauge();
        assertEquals(3.0, gauge.value());
    }
}
