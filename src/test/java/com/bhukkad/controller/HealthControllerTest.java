package com.bhukkad.controller;

import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.cluster.InstanceMetadata;
import com.bhukkad.datasource.ReadReplicaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link HealthController}.
 *
 * <p>Scope: pure logic and endpoint envelope shape. Dependencies are mocked so the
 * suite runs without a database or Redis and stays green in CI. The DB/Redis probes
 * themselves are exercised by the Testcontainers-backed integration suite, not here.
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private DataSource writeDataSource;
    @Mock
    private DataSource readDataSource;
    @Mock
    private ReadReplicaProperties readReplicaProperties;
    @Mock
    private Environment environment;
    @Mock
    private RedisCacheService cacheService;
    @Mock
    private InstanceMetadata instanceMetadata;

    private HealthController controller() {
        return new HealthController(writeDataSource, readDataSource, readReplicaProperties,
                environment, cacheService, instanceMetadata);
    }

    @Test
    void resolveMemoryStatus_classifiesThresholds() {
        HealthController controller = controller();
        assertThat(controller.resolveMemoryStatus(10.0)).isEqualTo("HEALTHY");
        assertThat(controller.resolveMemoryStatus(75.0)).isEqualTo("HEALTHY");
        assertThat(controller.resolveMemoryStatus(76.0)).isEqualTo("WARNING");
        assertThat(controller.resolveMemoryStatus(90.0)).isEqualTo("WARNING");
        assertThat(controller.resolveMemoryStatus(90.1)).isEqualTo("CRITICAL");
        assertThat(controller.resolveMemoryStatus(99.9)).isEqualTo("CRITICAL");
    }

    @Test
    void ping_returnsPongEnvelope() {
        HealthController controller = controller();
        Map<String, String> ping = controller.ping().getBody();
        assertThat(ping).isNotNull();
        assertThat(ping.get("status")).isEqualTo("pong");
        assertThat(ping).containsKeys("application", "instanceId", "timestamp");
    }

    @Test
    void healthCheck_returnsUpWithExpectedKeys() {
        org.mockito.Mockito.lenient().when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        HealthController controller = controller();
        Map<String, Object> health = controller.healthCheck().getBody();
        assertThat(health).isNotNull();
        assertThat(health.get("status")).isEqualTo("UP");
        assertThat(health).containsKeys("application", "environment", "activeProfiles",
                "port", "instanceId", "timestamp", "uptime");
    }

    @Test
    void detailedHealthCheck_includesSubsystemProbes() {
        org.mockito.Mockito.lenient().when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        HealthController controller = controller();
        Map<String, Object> health = controller.detailedHealthCheck().getBody();
        assertThat(health).isNotNull();
        assertThat(health).containsKeys("database", "readReplica", "redis", "memory", "jvm", "system");
        assertThat(health.get("status")).isEqualTo("UP");
    }

    @Test
    void databaseHealth_and_replicaHealth_delegateCorrectly() {
        HealthController controller = controller();
        assertThat(controller.databaseHealth().getBody()).containsKey("role");
        assertThat(controller.readReplicaHealth().getBody()).containsKey("configured");
        assertThat(controller.memoryHealth().getBody()).containsKey("status");
    }
}
