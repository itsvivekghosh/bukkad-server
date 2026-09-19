package com.bhukkad.common.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaConcurrencyGuardTest {

    @Test
    void recommendedConcurrency_capsAtPartitionCount() {
        KafkaAdmin admin = mock(KafkaAdmin.class);
        when(admin.getConfigurationProperties()).thenReturn(Map.of());
        KafkaConcurrencyGuard guard = new KafkaConcurrencyGuard(admin);

        // partitionCount returns -1 when topic doesn't exist, so configured is returned
        assertThat(guard.recommendedConcurrency("missing-topic", 3)).isEqualTo(3);
    }

    @Test
    void recommendedConcurrency_returnsConfiguredWhenBelowFloor() {
        KafkaAdmin admin = mock(KafkaAdmin.class);
        when(admin.getConfigurationProperties()).thenReturn(Map.of());
        KafkaConcurrencyGuard guard = new KafkaConcurrencyGuard(admin);

        assertThat(guard.recommendedConcurrency("any-topic", 1)).isEqualTo(1);
    }
}
