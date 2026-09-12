package com.bhukkad.order.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OrderSseRegistry {

    public static final String METRIC_CONNECTIONS = "sse_connections";

    private final AtomicInteger totalEmitters = new AtomicInteger();
    private final MeterRegistry meterRegistry;
    private final Set<SseEmitter> activeEmitters = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public OrderSseRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            meterRegistry.gauge(METRIC_CONNECTIONS, totalEmitters, AtomicInteger::doubleValue);
        }
    }

    public SseEmitter register(SseEmitter emitter) {
        if (activeEmitters.add(emitter)) {
            totalEmitters.incrementAndGet();
        }
        Runnable cleanup = () -> {
            if (activeEmitters.remove(emitter)) {
                totalEmitters.decrementAndGet();
            }
            emitter.complete();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        return emitter;
    }
}
