package com.bhukkad.common.aws;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CloudWatch Embedded Metric Format (EMF) logger.
 * <p>
 * EMF allows you to emit structured JSON logs that CloudWatch Logs automatically
 * converts to metrics. This eliminates the need for PutMetricData API calls
 * and provides better cost efficiency and richer context.
 * 
 * Usage:
 * <pre>
 * emfLogger.logMetric("OrderService", "OrderCreated", 1.0, StandardUnit.COUNT,
 *     Map.of("Service", "commerce", "Environment", "prod"));
 * </pre>
 */
@Component
public class CloudWatchEmfLogger {

    private final CloudWatchClient cloudWatchClient;
    private final AwsProperties awsProperties;

    public CloudWatchEmfLogger(CloudWatchClient cloudWatchClient, AwsProperties awsProperties) {
        this.cloudWatchClient = cloudWatchClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Logs a metric using EMF format to stdout (captured by CloudWatch Logs agent).
     * This is the recommended approach for Lambda/ECS/Fargate.
     */
    public void logMetric(String namespace, String metricName, double value, StandardUnit unit,
                          Map<String, String> dimensions) {
        EmfPayload payload = buildEmfPayload(namespace, metricName, value, unit, dimensions);
        System.out.println(payload.toJson());
    }

    /**
     * Logs multiple metrics in a single EMF payload (more efficient).
     */
    public void logMetrics(String namespace, Map<String, EmfMetric> metrics,
                           Map<String, String> commonDimensions) {
        EmfPayload payload = EmfPayload.builder()
                .namespace(namespace)
                .timestamp(Instant.now().toEpochMilli())
                .requestId(UUID.randomUUID().toString())
                .build();

        metrics.forEach((name, metric) -> payload.addMetric(name, metric.value(), metric.unit(), metric.dimensions()));
        
        if (commonDimensions != null) {
            commonDimensions.forEach(payload::addDimension);
        }

        System.out.println(payload.toJson());
    }

    /**
     * Creates a metric scope for grouped logging with automatic flush.
     */
    public EmfScope createScope(String namespace, Map<String, String> defaultDimensions) {
        return new EmfScope(this, namespace, defaultDimensions);
    }

    /**
     * Direct PutMetricData call (fallback for non-EMF environments).
     */
    public void putMetricData(String namespace, String metricName, double value, StandardUnit unit,
                              Map<String, String> dimensions) {
        MetricDatum.Builder datumBuilder = MetricDatum.builder()
                .metricName(metricName)
                .value(value)
                .unit(unit)
                .timestamp(Instant.now());

        if (dimensions != null) {
            dimensions.forEach((k, v) -> datumBuilder.dimensions(
                    Dimension.builder().name(k).value(v).build()));
        }

        cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                .namespace(namespace)
                .metricData(datumBuilder.build())
                .build());
    }

    /**
     * Builds a single EMF payload.
     */
    private EmfPayload buildEmfPayload(String namespace, String metricName, double value, StandardUnit unit,
                                       Map<String, String> dimensions) {
        EmfPayload payload = EmfPayload.builder()
                .namespace(namespace)
                .timestamp(Instant.now().toEpochMilli())
                .requestId(UUID.randomUUID().toString())
                .build();

        payload.addMetric(metricName, value, unit, dimensions);
        
        if (dimensions != null) {
            dimensions.forEach(payload::addDimension);
        }

        return payload;
    }

    // ==================== EMF Payload Builder ====================

    public static class EmfPayload {
        private final Map<String, Object> payload = new HashMap<>();
        private final Map<String, Object> metrics = new HashMap<>();
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private EmfPayload() {
            payload.put("_aws", Map.of(
                    "Timestamp", System.currentTimeMillis(),
                    "CloudWatchMetrics", new java.util.ArrayList<Map<String, Object>>()
            ));
        }

        public static Builder builder() {
            return new Builder();
        }

        public EmfPayload addMetric(String name, double value, StandardUnit unit, Map<String, String> dimensions) {
            Map<String, Object> metric = new HashMap<>();
            metric.put("Name", name);
            metric.put("Value", value);
            metric.put("Unit", unit.toString());
            
            if (dimensions != null && !dimensions.isEmpty()) {
                metric.put("Dimensions", new java.util.ArrayList<>(dimensions.keySet()));
                dimensions.forEach(this::addDimension);
            }

            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> cwMetrics = 
                (java.util.List<Map<String, Object>>) ((Map<String, Object>) payload.get("_aws")).get("CloudWatchMetrics");
            cwMetrics.add(metric);
            
            return this;
        }

        public EmfPayload addDimension(String name, String value) {
            payload.put(name, value);
            return this;
        }

        public String toJson() {
            try {
                return MAPPER.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize EMF payload", e);
            }
        }

        public static class Builder {
            private final EmfPayload payload = new EmfPayload();

            public Builder namespace(String namespace) {
                payload.payload.put("Namespace", namespace);
                return this;
            }

            public Builder timestamp(long timestamp) {
                ((Map<String, Object>) payload.payload.get("_aws")).put("Timestamp", timestamp);
                return this;
            }

            public Builder requestId(String requestId) {
                payload.payload.put("RequestId", requestId);
                return this;
            }

            public EmfPayload build() {
                return payload;
            }
        }
    }

    // ==================== Metric Definition ====================

    public record EmfMetric(double value, StandardUnit unit, Map<String, String> dimensions) {
        public static EmfMetric of(double value, StandardUnit unit) {
            return new EmfMetric(value, unit, null);
        }
        
        public static EmfMetric of(double value, StandardUnit unit, Map<String, String> dimensions) {
            return new EmfMetric(value, unit, dimensions);
        }
    }

    // ==================== EMF Scope for Grouped Logging ====================

    public class EmfScope implements AutoCloseable {
        private final CloudWatchEmfLogger logger;
        private final String namespace;
        private final Map<String, String> defaultDimensions;
        private final Map<String, EmfMetric> metrics = new HashMap<>();

        public EmfScope(CloudWatchEmfLogger logger, String namespace, Map<String, String> defaultDimensions) {
            this.logger = logger;
            this.namespace = namespace;
            this.defaultDimensions = defaultDimensions != null ? new HashMap<>(defaultDimensions) : new HashMap<>();
        }

        public EmfScope metric(String name, double value, StandardUnit unit) {
            return metric(name, value, unit, null);
        }

        public EmfScope metric(String name, double value, StandardUnit unit, Map<String, String> dimensions) {
            Map<String, String> merged = new HashMap<>(defaultDimensions);
            if (dimensions != null) merged.putAll(dimensions);
            metrics.put(name, new EmfMetric(value, unit, merged));
            return this;
        }

        public EmfScope dimension(String name, String value) {
            defaultDimensions.put(name, value);
            return this;
        }

        @Override
        public void close() {
            if (!metrics.isEmpty()) {
                logger.logMetrics(namespace, metrics, defaultDimensions);
            }
        }
    }
}