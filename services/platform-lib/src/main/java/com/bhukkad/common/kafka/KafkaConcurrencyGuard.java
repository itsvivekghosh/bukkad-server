package com.bhukkad.common.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Utility for validating Kafka consumer concurrency against actual topic
 * partition counts. High-traffic services should ensure their listener
 * concurrency does not exceed the number of partitions for the topics they
 * consume; excess threads remain idle and waste resources.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * KafkaConcurrencyGuard guard = new KafkaConcurrencyGuard(kafkaAdmin);
 * guard.validate("order.events.v1", 3); // logs warning if partitions < 3
 * }</pre>
 */
public class KafkaConcurrencyGuard {

    private static final Logger log = LoggerFactory.getLogger(KafkaConcurrencyGuard.class);
    private final KafkaAdmin kafkaAdmin;

    public KafkaConcurrencyGuard(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    /**
     * Validates that {@code concurrency} does not exceed the partition count
     * for {@code topic}. Logs a warning when the configured concurrency is
     * higher than the available partitions.
     *
     * @param topic       the Kafka topic to inspect
     * @param concurrency the configured listener concurrency
     */
    public void validate(String topic, int concurrency) {
        if (concurrency <= 1) {
            return;
        }
        try {
            int partitions = partitionCount(topic);
            if (partitions > 0 && concurrency > partitions) {
                log.warn("KAFKA_CONCURRENCY_MISMATCH topic={} partitions={} concurrency={} | excess threads will be idle",
                        topic, partitions, concurrency);
            } else if (partitions > 0) {
                log.info("KAFKA_CONCURRENCY_OK topic={} partitions={} concurrency={}", topic, partitions, concurrency);
            }
        } catch (Exception ex) {
            log.debug("KAFKA_CONCURRENCY_CHECK_FAILED topic={} error={}", topic, ex.getMessage());
        }
    }

    /**
     * Returns the partition count for {@code topic}, or -1 if the topic does
     * not exist or cannot be described.
     */
    public int partitionCount(String topic) throws ExecutionException, InterruptedException {
        try (AdminClient client = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            DescribeTopicsResult result = client.describeTopics(List.of(topic));
            Map<String, TopicDescription> partitions = result.all().get();
            TopicDescription description = partitions.get(topic);
            if (description == null) {
                return -1;
            }
            return description.partitions().size();
        }
    }

    /**
     * Convenience: returns the recommended concurrency for {@code topic},
     * capped at the actual partition count. Returns {@code Math.min(configured, partitions)}
     * when partitions are known, otherwise {@code configured}.
     */
    public int recommendedConcurrency(String topic, int configured) {
        if (configured <= 1) {
            return configured;
        }
        try {
            int partitions = partitionCount(topic);
            if (partitions > 0) {
                return Math.min(configured, partitions);
            }
        } catch (Exception ex) {
            log.debug("KAFKA_CONCURRENCY_RECOMMENDATION_FAILED topic={} error={}", topic, ex.getMessage());
        }
        return configured;
    }
}
