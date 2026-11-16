package com.bhukkad.engagement.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Observability configuration for the Engagement Service.
 * 
 * Provides comprehensive metrics instrumentation using Micrometer.
 * OpenTelemetry distributed tracing can be added when dependencies are available.
 * 
 * <p>Metrics categories:</p>
 * <ul>
 *   <li>fraud.* - Fraud detection operations</li>
 *   <li>loyalty.* - Loyalty points operations</li>
 *   <li>feed.* - Social feed operations</li>
 *   <li>like.* - Like counter operations</li>
 *   <li>notification.* - Notification dispatch operations</li>
 *   <li>referral.* - Referral code operations</li>
 *   <li>sse.* - Server-sent events operations</li>
 *   <li>survey.* - Survey submission operations</li>
 * </ul>
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Utility bean for tracing operations manually with metrics recording.
     */
    @Bean
    public TracedOperation tracedOperation(MeterRegistry meterRegistry) {
        return new TracedOperation(meterRegistry);
    }

    /**
     * Simple traced operation wrapper that records metrics around operations.
     * Records duration, success/failure counts, and custom tags.
     */
    public static class TracedOperation {

        private final MeterRegistry meterRegistry;
        private final Map<String, Counter> errorCounters = new ConcurrentHashMap<>();

        public TracedOperation(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
        }

        /**
         * Executes an operation with timing and error metrics.
         */
        public <T> T execute(String operationName, java.util.function.Supplier<T> operation) {
            String metricName = "operation." + sanitize(operationName);
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                T result = operation.get();
                meterRegistry.counter(metricName + ".success").increment();
                return result;
            } catch (Exception ex) {
                meterRegistry.counter(metricName + ".errors", "error", ex.getClass().getSimpleName())
                    .increment();
                throw ex;
            } finally {
                sample.stop(Timer.builder(metricName).register(meterRegistry));
            }
        }

        /**
         * Executes a void operation with timing and error metrics.
         */
        public void execute(String operationName, java.lang.Runnable operation) {
            String metricName = "operation." + sanitize(operationName);
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                operation.run();
                meterRegistry.counter(metricName + ".success").increment();
            } catch (Exception ex) {
                meterRegistry.counter(metricName + ".errors", "error", ex.getClass().getSimpleName())
                    .increment();
                throw ex;
            } finally {
                sample.stop(Timer.builder(metricName).register(meterRegistry));
            }
        }

        private String sanitize(String name) {
            return name.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
        }
    }

    /**
     * Registers standard observability gauges for the Engagement Service.
     * These provide baseline visibility into service health.
     */
    @Bean
    public ObservabilityGauges observabilityGauges(MeterRegistry meterRegistry) {
        return new ObservabilityGauges(meterRegistry);
    }

    /**
     * Gauges for key service-level metrics.
     */
    public static class ObservabilityGauges {

        private final MeterRegistry meterRegistry;

        public ObservabilityGauges(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            registerGauges();
        }

        private void registerGauges() {
            // JVM metrics are auto-registered by Micrometer
            // Custom gauges can be added here as needed
            
            Counter.builder("engagement.service.version")
                .tag("version", "1.0.0")
                .description("Engagement service version")
                .register(meterRegistry);
            
            Counter.builder("engagement.service.environment")
                .tag("environment", "production")
                .description("Deployment environment")
                .register(meterRegistry);
        }
    }
}
