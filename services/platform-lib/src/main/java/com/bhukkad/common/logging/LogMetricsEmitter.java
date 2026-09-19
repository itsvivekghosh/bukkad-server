package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Emits metrics when notable log events occur, bridging the logs-to-metrics
 * gap so ops can alert on log patterns without parsing logs.
 */
@Component
public class LogMetricsEmitter {

    private final Counter errorCounter;
    private final Counter slowServiceCounter;
    private final Counter circuitBreakerOpenCounter;
    private final Counter auditCounter;

    public LogMetricsEmitter(MeterRegistry registry) {
        this.errorCounter = Counter.builder("log.events")
            .tag("level", "ERROR")
            .tag("category", "application")
            .description("Count of ERROR log events from LoggingAspect")
            .register(registry);

        this.slowServiceCounter = Counter.builder("log.events")
            .tag("level", "WARN")
            .tag("category", "slow_service")
            .description("Count of slow service calls logged by LoggingAspect")
            .register(registry);

        this.circuitBreakerOpenCounter = Counter.builder("log.events")
            .tag("level", "WARN")
            .tag("category", "circuit_breaker")
            .description("Count of circuit breaker OPEN events")
            .register(registry);

        this.auditCounter = Counter.builder("log.events")
            .tag("level", "INFO")
            .tag("category", "audit")
            .description("Count of audit log events")
            .register(registry);
    }

    public void recordError(String service) {
        errorCounter.increment();
    }

    public void recordSlowService() {
        slowServiceCounter.increment();
    }

    public void recordCircuitBreakerOpen(String name) {
        circuitBreakerOpenCounter.increment();
    }

    public void recordAuditEvent() {
        auditCounter.increment();
    }
}
