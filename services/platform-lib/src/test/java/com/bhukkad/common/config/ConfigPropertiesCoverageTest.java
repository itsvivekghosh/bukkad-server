package com.bhukkad.common.config;

import com.bhukkad.common.config.SyntheticHealthCheckProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding surfaces for the config-driven properties that services inherit.
 */
class ConfigPropertiesCoverageTest {

    @Test
    void syntheticHealthCheck_defaultsAndOverrides() {
        SyntheticHealthCheckProperties props = new SyntheticHealthCheckProperties();
        assertThat(props.getEndpoints()).containsExactly("/actuator/health", "/api/v1/health");
        assertThat(props.getIntervalMs()).isEqualTo(60000);
        assertThat(props.getTimeoutMs()).isEqualTo(5000);

        props.setEndpoints(List.of("/health/liveness"));
        props.setIntervalMs(15000);
        props.setTimeoutMs(2500);
        assertThat(props.getEndpoints()).containsExactly("/health/liveness");
        assertThat(props.getIntervalMs()).isEqualTo(15000);
        assertThat(props.getTimeoutMs()).isEqualTo(2500);
    }

    @Test
    void sloProperties_legacyConfigBinder() {
        SloProperties slo = new SloProperties();
        assertThat(slo.getTargetLatencyMs()).isEqualTo(500);
        assertThat(slo.getTargetAvailabilityPercentage()).isEqualTo(99.9);
        assertThat(slo.getEvaluationWindowMinutes()).isEqualTo(60);
        assertThat(slo.getAlertBurnRateThreshold()).isEqualTo(1.0);

        slo.setTargetLatencyMs(250);
        slo.setTargetAvailabilityPercentage(99.95);
        slo.setEvaluationWindowMinutes(30);
        slo.setAlertBurnRateThreshold(14.4);
        assertThat(slo.getTargetLatencyMs()).isEqualTo(250);
        assertThat(slo.getTargetAvailabilityPercentage()).isEqualTo(99.95);
        assertThat(slo.getEvaluationWindowMinutes()).isEqualTo(30);
        assertThat(slo.getAlertBurnRateThreshold()).isEqualTo(14.4);
    }

    @Test
    void externalEvents_kafkaGateAndNestedKafkaProps() {
        ExternalEventsProperties props = new ExternalEventsProperties();
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getType()).isEqualTo("log");
        assertThat(props.isKafkaEnabled()).isFalse();

        props.setEnabled(true);
        assertThat(props.isKafkaEnabled()).isFalse(); // type still log
        props.setType("KAFKA");
        assertThat(props.isKafkaEnabled()).isTrue(); // case-insensitive

        ExternalEventsProperties.Kafka kafka = props.getKafka();
        assertThat(kafka.getBootstrapServers()).isEqualTo("localhost:9092");
        assertThat(kafka.getPlatformTopic()).isEqualTo("bhukkad.platform.events");
        assertThat(kafka.getConsumerGroup()).isEqualTo("bhukkad-platform-consumer");
        assertThat(kafka.getDlqTopic()).isEqualTo("bhukkad.platform.events.dlt");
        kafka.setBootstrapServers("kafka-1:9093");
        kafka.setPlatformTopic("custom.topic");
        kafka.setConsumerGroup("custom-group");
        kafka.setDlqTopic("custom.dlt");
        assertThat(kafka.getBootstrapServers()).isEqualTo("kafka-1:9093");
        assertThat(kafka.getPlatformTopic()).isEqualTo("custom.topic");
        assertThat(kafka.getConsumerGroup()).isEqualTo("custom-group");
        assertThat(kafka.getDlqTopic()).isEqualTo("custom.dlt");
    }
}
