package com.bhukkad.common.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link KafkaConsumerMetrics} registers the lag gauge and the
 * fetch-rate / records-per-request counters tagged by consumer group and topic.
 */
class KafkaConsumerMetricsTest {

    private static final String CONSUMER_GROUP = "test-group";
    private static final String TOPIC = "test.topic.v1";

    private static KafkaPlatformProperties properties() {
        return new KafkaPlatformProperties(
                true, "kafka",
                new KafkaPlatformProperties.Kafka("localhost:9092", CONSUMER_GROUP, TOPIC, TOPIC + ".dlt",
                        1, 100, 300000, 30000, 10000, false, 500, 52428800, java.util.Map.of()),
                false, 1, com.bhukkad.common.kafka.dlq.DlqProperties.defaults());
    }

    @Test
    void constructor_registersLagGaugeAndCountersForServiceGroupAndTopic() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new KafkaConsumerMetrics(registry, properties());

        // Lag gauge exists with the service's group + topic tags and starts at 0.
        assertThat(registry.get(KafkaConsumerMetrics.LAG_SECONDS_METRIC)
                .tag("consumer_group", CONSUMER_GROUP).tag("topic", TOPIC).gauge().value())
                .isEqualTo(0.0);
        // Counters exist for the service's group + topic.
        assertThat(registry.counter(KafkaConsumerMetrics.FETCH_RATE_METRIC,
                "consumer_group", CONSUMER_GROUP, "topic", TOPIC).count()).isZero();
        assertThat(registry.counter(KafkaConsumerMetrics.RECORDS_PER_REQUEST_METRIC,
                "consumer_group", CONSUMER_GROUP, "topic", TOPIC).count()).isZero();
    }

    @Test
    void recordLag_updatesGaugeAndRegistersNewGroupTopic() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaConsumerMetrics metrics = new KafkaConsumerMetrics(registry, properties());

        metrics.recordLag("other-group", "other.topic.v1", 42.5);

        assertThat(registry.get(KafkaConsumerMetrics.LAG_SECONDS_METRIC)
                .tag("consumer_group", "other-group").tag("topic", "other.topic.v1").gauge().value())
                .isEqualTo(42.5);
    }

    @Test
    void markFetch_incrementsFetchRateCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaConsumerMetrics metrics = new KafkaConsumerMetrics(registry, properties());

        metrics.markFetch("other-group", "other.topic.v1");
        metrics.markFetch("other-group", "other.topic.v1");

        assertThat(registry.counter(KafkaConsumerMetrics.FETCH_RATE_METRIC,
                "consumer_group", "other-group", "topic", "other.topic.v1").count())
                .isEqualTo(2.0);
    }

    @Test
    void recordRecordsPerRequest_incrementsByRecordCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaConsumerMetrics metrics = new KafkaConsumerMetrics(registry, properties());

        metrics.recordRecordsPerRequest("other-group", "other.topic.v1", 50);

        assertThat(registry.counter(KafkaConsumerMetrics.RECORDS_PER_REQUEST_METRIC,
                "consumer_group", "other-group", "topic", "other.topic.v1").count())
                .isEqualTo(50.0);
    }
}