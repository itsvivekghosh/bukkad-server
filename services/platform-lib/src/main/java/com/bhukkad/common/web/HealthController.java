package com.bhukkad.common.web;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public HealthController(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", applicationName);
        body.put("environment", activeProfiles);
        body.put("uptimeSeconds", uptimeSeconds());
        body.put("database", databaseStatus());
        body.put("memory", memory());
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping({"/health/memory", "/api/v1/health/memory"})
    public Map<String, Object> memory() {
        Map<String, Object> body = new LinkedHashMap<>(memoryStats());
        body.put("status", "UP");
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
        body.put("status", "UP");
        body.put("service", applicationName);
        body.put("database", databaseStatus());
        body.put("uptimeSeconds", uptimeSeconds());
        body.put("memory", memory());
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    private long uptimeSeconds() {
        return ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
    }

    private String databaseStatus() {
        DataSource ds = dataSourceProvider.getIfAvailable();
        if (ds == null) {
            return "NOT_CONFIGURED";
        }
        try (Connection connection = ds.getConnection()) {
            return connection.isValid(2) ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private Map<String, Object> memoryStats() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("usedMb", used / (1024 * 1024));
        mem.put("maxMb", runtime.maxMemory() / (1024 * 1024));
        mem.put("availableProcessors", runtime.availableProcessors());
        return mem;
    }
}
