package com.bhukkad.metrics;

import com.bhukkad.live.OrderSseStreamService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PlatformMetrics {

    public PlatformMetrics(MeterRegistry registry, OrderSseStreamService sseStreamService) {
        Gauge.builder("sse_active_connections", sseStreamService, OrderSseStreamService::activeConnectionCount)
                .description("Active SSE connections across kitchen, rider, and customer streams")
                .register(registry);
    }
}
