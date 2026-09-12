package com.bhukkad.common.web;

import com.bhukkad.common.kafka.KafkaPlatformProperties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.KafkaFuture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight liveness surface shared by every servlet service (replaces the
 * monolith's {@code HealthController}). Exposed at both {@code /health/**}
 * (infra probe convention permitted by every security config) and
 * {@code /api/v1/health/**} (gateway-parity path used by clients and the API
 * test suite). Deep dependency checks stay on {@code /actuator/health}.
 */
@RestController
public class HealthController {

    @Value("${spring.application.name:unknown}")
    private String applicationName;

    @Value("${spring.profiles.active:default}")
    private String activeProfiles;

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final RedisHealth redisHealth;
    private final KafkaHealth kafkaHealth;

    public HealthController(ObjectProvider<DataSource> dataSourceProvider,
                            RedisHealth redisHealth,
                            KafkaHealth kafkaHealth) {
        this.dataSourceProvider = dataSourceProvider;
        this.redisHealth = redisHealth;
        this.kafkaHealth = kafkaHealth;
    }

    @GetMapping({"/health/ping", "/api/v1/health/ping"})
    public Map<String, Object> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("message", "pong");
        body.put("service", applicationName);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping({"/health", "/api/v1/health"})
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", applicationName);
        body.put("environment", activeProfiles);
        body.put("uptimeSeconds", uptimeSeconds());
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping({"/health/detailed", "/api/v1/health/detailed"})
    public Map<String, Object> detailed() {
        String db = databaseStatus();
        String redis = redisHealth.status();
        String kafka = kafkaHealth.status();
        String memory = memoryStatus();
        String overall = min(db, redis, kafka, memory);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", overall);
        body.put("service", applicationName);
        body.put("environment", activeProfiles);
        body.put("uptimeSeconds", uptimeSeconds());
        body.put("database", db);
        body.put("redis", redis);
        body.put("kafka", kafka);
        body.put("memory", memory);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping({"/health/memory", "/api/v1/health/memory"})
    public Map<String, Object> memory() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", memoryStatus());
        body.put("service", applicationName);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping({"/health/db", "/api/v1/health/db"})
    public Map<String, Object> db() {
        Map<String, Object> body = new LinkedHashMap<>();
        String status = databaseStatus();
        body.put("status", "UP".equals(status) ? "UP" : status);
        body.put("database", status);
        body.put("primary", status);
        body.put("service", applicationName);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping({"/health/db/replica", "/api/v1/health/db/replica"})
    public Map<String, Object> dbReplica() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("replica", "NOT_CONFIGURED");
        body.put("note", "read-replica routing activates only when a second pool is configured");
        body.put("service", applicationName);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping({"/health/env", "/api/v1/health/env"})
    public Map<String, Object> env() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", applicationName);
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("activeProfiles", activeProfiles);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping("/api/v1/platform/status")
    public Map<String, Object> platformStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        String db = databaseStatus();
        String redis = redisHealth.status();
        String kafka = kafkaHealth.status();
        String overall = min(db, redis, kafka, "UP");
        body.put("status", overall);
        body.put("service", applicationName);
        body.put("database", db);
        body.put("redis", redis);
        body.put("kafka", kafka);
        body.put("uptimeSeconds", uptimeSeconds());
        body.put("memory", memory());
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    private String databaseStatus() {
        DataSource ds = dataSourceProvider.getIfAvailable();
        if (ds == null) {
            return "NOT_CONFIGURED";
        }
        try (var connection = ds.getConnection()) {
            return connection.isValid(2) ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private String memoryStatus() {
        Runtime runtime = Runtime.getRuntime();
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        if (maxMb == 0) {
            return "UP";
        }
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        if (usedMb > maxMb * 9 / 10) {
            return "MEMORY_PRESSURE";
        }
        return "UP";
    }

    private long uptimeSeconds() {
        return ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
    }

    private static String min(String... values) {
        String worst = "UP";
        for (String v : values) {
            if (v == null) {
                continue;
            }
            int cmp = rank(v) - rank(worst);
            if (cmp < 0) {
                worst = v;
            }
        }
        return worst;
    }

    private static int rank(String v) {
        return switch (v) {
            case "UP" -> 4;
            case "MEMORY_PRESSURE" -> 3;
            case "DOWN" -> 2;
            case "NOT_CONFIGURED" -> 1;
            default -> 0;
        };
    }

    @Component
    public static class RedisHealth {
        private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

        public RedisHealth(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
            this.redisTemplateProvider = redisTemplateProvider;
        }

        public String status() {
            StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
            if (template == null) {
                return "NOT_CONFIGURED";
            }
            try {
                Boolean hasKey = template.hasKey("health-check-probe");
                return Boolean.TRUE.equals(hasKey) || Boolean.FALSE.equals(hasKey) ? "UP" : "DOWN";
            } catch (Exception e) {
                return "DOWN";
            }
        }
    }

    @Component
    public static class KafkaHealth {
        private final ObjectProvider<KafkaAdmin> kafkaAdminProvider;
        private final KafkaPlatformProperties properties;
        private volatile String cachedStatus = "NOT_CONFIGURED";
        private volatile long cachedAt = 0L;
        private static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(5);

        public KafkaHealth(ObjectProvider<KafkaAdmin> kafkaAdminProvider, KafkaPlatformProperties properties) {
            this.kafkaAdminProvider = kafkaAdminProvider;
            this.properties = properties;
        }

        public synchronized String status() {
            long now = System.currentTimeMillis();
            if (now - cachedAt < CACHE_TTL_MS && cachedStatus != null) {
                return cachedStatus;
            }
            cachedAt = now;
            if (!properties.isKafkaEnabled()) {
                cachedStatus = "NOT_CONFIGURED";
                return cachedStatus;
            }
            KafkaAdmin admin = kafkaAdminProvider.getIfAvailable();
            if (admin == null) {
                cachedStatus = "NOT_CONFIGURED";
                return cachedStatus;
            }
            try (AdminClient client = AdminClient.create(admin.getConfigurationProperties())) {
                client.describeCluster().nodes().get(500, TimeUnit.MILLISECONDS);
                cachedStatus = "UP";
            } catch (Exception e) {
                cachedStatus = "DOWN";
            }
            return cachedStatus;
        }
    }
}
