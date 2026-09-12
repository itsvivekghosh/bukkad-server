package com.bhukkad.common.web;

import com.bhukkad.common.kafka.KafkaPlatformProperties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared liveness surface: ping/health envelopes, dependency roll-ups
 * (database/redis/kafka/memory), and the kafka probe's cache + transport
 * gating. No real infra is contacted — Kafka admin clients are mocked.
 */
class HealthControllerTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static HealthController controller(ObjectProvider<DataSource> ds,
                                               HealthController.RedisHealth redis,
                                               HealthController.KafkaHealth kafka) {
        HealthController c = new HealthController(ds, redis, kafka);
        ReflectionTestUtils.setField(c, "applicationName", "test-service");
        ReflectionTestUtils.setField(c, "activeProfiles", "test");
        return c;
    }

    private static HealthController.RedisHealth redis(StringRedisTemplate template) {
        return new HealthController.RedisHealth(provider(template));
    }

    private static HealthController.KafkaHealth kafka(KafkaPlatformProperties props, KafkaAdmin admin) {
        return new HealthController.KafkaHealth(provider(admin), props);
    }

    private static final KafkaPlatformProperties KAFKA =
            new KafkaPlatformProperties(true, "kafka",
                    new KafkaPlatformProperties.Kafka("localhost:9092", "g", "t", "dlq"), false);

    @Test
    void ping_and_health_and_env_envelopes() {
        HealthController c = controller(provider((DataSource) null), redis(null), kafka(KafkaPlatformProperties.disabled(), null));

        assertThat(c.ping()).containsEntry("status", "UP")
                .containsEntry("message", "pong")
                .containsEntry("service", "test-service")
                .containsKey("timestamp");
        assertThat(c.health()).containsEntry("service", "test-service")
                .containsEntry("environment", "test")
                .containsKey("uptimeSeconds");
        assertThat(c.env()).containsEntry("service", "test-service")
                .containsEntry("activeProfiles", "test")
                .containsEntry("javaVersion", System.getProperty("java.version"));
        assertThat(c.dbReplica()).containsEntry("status", "UP")
                .containsEntry("replica", "NOT_CONFIGURED");
    }

    @Test
    void dbStatus_upDownNotConfigured() throws Exception {
        HealthController.RedisHealth redis = redis(null);
        HealthController.KafkaHealth kafka = kafka(KafkaPlatformProperties.disabled(), null);

        // no datasource bean
        assertThat(controller(provider((DataSource) null), redis, kafka).db())
                .containsEntry("status", "NOT_CONFIGURED")
                .containsEntry("database", "NOT_CONFIGURED");

        // valid connection
        Connection valid = mock(Connection.class);
        when(valid.isValid(anyInt())).thenReturn(true);
        DataSource validDs = mock(DataSource.class);
        when(validDs.getConnection()).thenReturn(valid);
        assertThat(controller(provider(validDs), redis, kafka).db())
                .containsEntry("status", "UP")
                .containsEntry("primary", "UP");

        // connection reports invalid
        Connection invalid = mock(Connection.class);
        when(invalid.isValid(anyInt())).thenReturn(false);
        DataSource invalidDs = mock(DataSource.class);
        when(invalidDs.getConnection()).thenReturn(invalid);
        assertThat(controller(provider(invalidDs), redis, kafka).db())
                .containsEntry("status", "DOWN")
                .containsEntry("database", "DOWN");

        // getConnection throws
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("pool down"));
        assertThat(controller(provider(broken), redis, kafka).db())
                .containsEntry("status", "DOWN");
    }

    @Test
    void redisHealth_upDownNotConfigured() {
        assertThat(redis(null).status()).isEqualTo("NOT_CONFIGURED");

        StringRedisTemplate ok = mock(StringRedisTemplate.class);
        when(ok.hasKey(anyString())).thenReturn(Boolean.TRUE);
        assertThat(redis(ok).status()).isEqualTo("UP");

        StringRedisTemplate noKey = mock(StringRedisTemplate.class);
        when(noKey.hasKey(anyString())).thenReturn(null);
        assertThat(redis(noKey).status()).isEqualTo("DOWN");

        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertThat(redis(broken).status()).isEqualTo("DOWN");
    }

    @Test
    void kafkaHealth_transportGateAndCache() {
        HealthController.KafkaHealth logTransport = kafka(KafkaPlatformProperties.disabled(), null);
        assertThat(logTransport.status()).isEqualTo("NOT_CONFIGURED");
        // second read comes from the TTL cache (no provider consult)
        assertThat(logTransport.status()).isEqualTo("NOT_CONFIGURED");

        assertThat(kafka(KAFKA, null).status()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void kafkaHealth_clusterReachableReportsUp() throws Exception {
        KafkaAdmin admin = mock(KafkaAdmin.class);
        when(admin.getConfigurationProperties()).thenReturn(Map.of("bootstrap.servers", "localhost:9092"));
        AdminClient client = mock(AdminClient.class);
        DescribeClusterResult result = mock(DescribeClusterResult.class);
        when(result.nodes()).thenReturn(
                KafkaFuture.completedFuture(List.of(new Node(1, "broker-1", 9092))));
        when(client.describeCluster()).thenReturn(result);

        try (MockedStatic<AdminClient> mocked = Mockito.mockStatic(AdminClient.class)) {
            mocked.when(() -> AdminClient.create(Mockito.anyMap())).thenReturn(client);
            assertThat(kafka(KAFKA, admin).status()).isEqualTo("UP");
        }
    }

    @Test
    void kafkaHealth_clusterUnreachableReportsDown() throws Exception {
        KafkaAdmin admin = mock(KafkaAdmin.class);
        when(admin.getConfigurationProperties()).thenReturn(Map.of("bootstrap.servers", "localhost:9092"));
        AdminClient client = mock(AdminClient.class);
        DescribeClusterResult result = mock(DescribeClusterResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<java.util.Collection<Node>> failed = mock(KafkaFuture.class);
        when(failed.get(anyLong(), any())).thenThrow(new RuntimeException("broker unavailable"));
        when(result.nodes()).thenReturn(failed);
        when(client.describeCluster()).thenReturn(result);

        try (MockedStatic<AdminClient> mocked = Mockito.mockStatic(AdminClient.class)) {
            mocked.when(() -> AdminClient.create(Mockito.anyMap())).thenReturn(client);
            assertThat(kafka(KAFKA, admin).status()).isEqualTo("DOWN");
        }
    }

    @Test
    void detailed_rollupWorstStatusWins() {
        HealthController c = controller(provider((DataSource) null), redis(null),
                kafka(KafkaPlatformProperties.disabled(), null));
        Map<String, Object> body = c.detailed();
        assertThat(body)
                .containsEntry("database", "NOT_CONFIGURED")
                .containsEntry("redis", "NOT_CONFIGURED")
                .containsEntry("kafka", "NOT_CONFIGURED")
                .containsEntry("status", "NOT_CONFIGURED")
                .containsEntry("service", "test-service");
    }

    @Test
    void memoryEndpoint_and_statusKeys() {
        HealthController c = controller(provider((DataSource) null), redis(null),
                kafka(KafkaPlatformProperties.disabled(), null));
        assertThat(c.memory()).containsEntry("service", "test-service");
        assertThat(c.memory().get("status")).isEqualTo(c.detailed().get("memory"));
    }

    @Test
    void platformStatus_composesAllChecks() {
        HealthController c = controller(provider((DataSource) null), redis(null),
                kafka(KafkaPlatformProperties.disabled(), null));
        Map<String, Object> body = c.platformStatus();
        assertThat(body).containsEntry("status", "NOT_CONFIGURED")
                .containsEntry("database", "NOT_CONFIGURED")
                .containsEntry("redis", "NOT_CONFIGURED")
                .containsEntry("kafka", "NOT_CONFIGURED")
                .containsKey("memory")
                .containsKey("uptimeSeconds");
    }

    @Test
    void min_rollupRanksUnknownBelowDownAndSkipsNulls() {
        HealthController c = controller(provider((DataSource) null), redis(null),
                kafka(KafkaPlatformProperties.disabled(), null));
        String worst = ReflectionTestUtils.invokeMethod(c, "min",
                (Object) new String[]{"UP", null, "BOGUS", "DOWN"});
        assertThat(worst).isEqualTo("BOGUS"); // unknown status ranks worst (0)

        String allNull = ReflectionTestUtils.invokeMethod(c, "min", (Object) new String[]{null, null});
        assertThat(allNull).isEqualTo("UP");

        String pressureBeatsUp = ReflectionTestUtils.invokeMethod(c, "min",
                (Object) new String[]{"UP", "MEMORY_PRESSURE"});
        assertThat(pressureBeatsUp).isEqualTo("MEMORY_PRESSURE");
    }
}
