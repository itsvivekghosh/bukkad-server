package com.bhukkad.common.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exports Kafka consumer-side metrics so consumer lag and fetch behaviour are
 * visible in Prometheus alongside the broker-side kafka-exporter metrics.
 *
 * <p>Metrics owned here (Prometheus renders dots as underscores):</p>
 * <ul>
 *   <li>{@code kafka_consumer_lag_seconds} — gauge: consumer lag in seconds for a
 *       {@code (consumer_group, topic)} pair.</li>
 *   <li>{@code kafka_consumer_fetch_rate} — counter: number of fetch requests
 *       issued for a {@code (consumer_group, topic)} pair.</li>
 *   <li>{@code kafka_consumer_records_per_request} — counter: records returned
 *       per fetch request for a {@code (consumer_group, topic)} pair.</li>
 * </ul>
 *
 * <p>All meters are tagged with {@code consumer_group} and {@code topic} so a
 * dashboard can group or filter per consumer group. Lag is fed in by callers
 * (e.g. a scheduled admin-client offset probe) via {@link #recordLag}; the
 * class never touches offsets, commits, or group semantics, so ordering and
 * commit behaviour are untouched.</p>
 */
@Slf4j
@Component
@ConditionalOnExpression(
        "'${app.events.external.enabled:false}' == 'true' && '${app.events.external.type:log}' == 'kafka'")
public class KafkaConsumerMetrics {

    public static final String LAG_SECONDS_METRIC = "kafka_consumer_lag_seconds";
    public static final String FETCH_RATE_METRIC = "kafka_consumer_fetch_rate";
    public static final String RECORDS_PER_REQUEST_METRIC = "kafka_consumer_records_per_request";

    private final MeterRegistry registry;
    /** Last known lag in seconds, keyed by {@code "consumerGroup|topic"}. */
    private final ConcurrentMap<String, AtomicReference<Double>> lagSeconds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Gauge> lagGauges = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> fetchRateCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> recordsPerRequestCounters = new ConcurrentHashMap<>();

    /** Consumer group and platform topic this service consumes (may be blank when gated off). */
    private final String consumerGroup;
    private final String platformTopic;

    public KafkaConsumerMetrics(MeterRegistry registry, KafkaPlatformProperties properties) {
        this.registry = registry;
        KafkaPlatformProperties.Kafka kafka = properties.kafka();
        this.consumerGroup = kafka.consumerGroup();
        this.platformTopic = kafka.platformTopic();
        // Export this service's own consumer group + platform topic immediately so
        // the tagged gauges exist at scrape time even before the first poll.
        if (isValid(consumerGroup) && isValid(platformTopic)) {
            registerLagGauge(consumerGroup, platformTopic);
            registerFetchRateCounter(consumerGroup, platformTopic);
            registerRecordsPerRequestCounter(consumerGroup, platformTopic);
        }
    }

    /**
     * Updates consumer lag (seconds) for a {@code (consumerGroup, topic)} pair.
     * Safe to call from any thread; only the gauge's backing value changes.
     */
    public void recordLag(String consumerGroup, String topic, double lagSeconds) {
        String key = key(consumerGroup, topic);
        AtomicReference<Double> value = this.lagSeconds.computeIfAbsent(key, k -> new AtomicReference<>(0.0));
        value.set(lagSeconds);
        lagGauges.computeIfAbsent(key, k -> registerLagGauge(consumerGroup, topic));
    }

    /** Convenience: records lag for this service's own consumer group + topic. */
    public void recordLag(double lagSeconds) {
        if (isValid(consumerGroup) && isValid(platformTopic)) {
            recordLag(consumerGroup, platformTopic, lagSeconds);
        }
    }

    /** Records one fetch request for a {@code (consumerGroup, topic)} pair. */
    public void markFetch(String consumerGroup, String topic) {
        fetchRateCounters.computeIfAbsent(key(consumerGroup, topic),
                k -> registerFetchRateCounter(consumerGroup, topic)).increment();
    }

    /** Convenience: marks a fetch for this service's own consumer group + topic. */
    public void markFetch() {
        if (isValid(consumerGroup) && isValid(platformTopic)) {
            markFetch(consumerGroup, platformTopic);
        }
    }

    /**
     * Records the number of records returned by one fetch request for a
     * {@code (consumerGroup, topic)} pair.
     */
    public void recordRecordsPerRequest(String consumerGroup, String topic, int records) {
        recordsPerRequestCounters.computeIfAbsent(key(consumerGroup, topic),
                k -> registerRecordsPerRequestCounter(consumerGroup, topic)).increment(records);
    }

    /** Convenience: records records-per-request for this service's own group + topic. */
    public void recordRecordsPerRequest(int records) {
        if (isValid(consumerGroup) && isValid(platformTopic)) {
            recordRecordsPerRequest(consumerGroup, platformTopic, records);
        }
    }

    // ── registration ──────────────────────────────────────────────────────────

    private Gauge registerLagGauge(String consumerGroup, String topic) {
        return Gauge.builder(LAG_SECONDS_METRIC, lagSeconds.computeIfAbsent(key(consumerGroup, topic),
                k -> new AtomicReference<>(0.0)), AtomicReference::get)
                .description("Kafka consumer lag in seconds for consumer group on a topic")
                .tags("consumer_group", consumerGroup, "topic", topic)
                .register(registry);
    }

    private Counter registerFetchRateCounter(String consumerGroup, String topic) {
        return Counter.builder(FETCH_RATE_METRIC)
                .description("Kafka consumer fetch requests issued for a consumer group on a topic")
                .tags("consumer_group", consumerGroup, "topic", topic)
                .register(registry);
    }

    private Counter registerRecordsPerRequestCounter(String consumerGroup, String topic) {
        return Counter.builder(RECORDS_PER_REQUEST_METRIC)
                .description("Kafka consumer records returned per fetch request for a consumer group on a topic")
                .tags("consumer_group", consumerGroup, "topic", topic)
                .register(registry);
    }

    private static String key(String consumerGroup, String topic) {
        return consumerGroup + "|" + topic;
    }

    private static boolean isValid(String value) {
        return value != null && !value.isBlank();
    }
}
