package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs Resilience4j circuit breaker state transitions so ops can detect
 * failing downstreams without tailing metrics.
 */
@Component
public class CircuitBreakerLogger {

    private static final Logger log = LoggerFactory.getLogger("RESILIENCE");
    private final LogMetricsEmitter metricsEmitter;

    public CircuitBreakerLogger(LogMetricsEmitter metricsEmitter) {
        this.metricsEmitter = metricsEmitter;
    }

    @EventListener
    public void onStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        log.warn("CIRCUIT_BREAKER | name={} | from={} | to={} | traceId={}",
            event.getCircuitBreakerName(),
            event.getStateTransition().getFromState(),
            event.getStateTransition().getToState(),
            TraceContext.currentTraceId()
        );
        if (event.getStateTransition().getToState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN) {
            metricsEmitter.recordCircuitBreakerOpen(event.getCircuitBreakerName());
        }
    }
}
