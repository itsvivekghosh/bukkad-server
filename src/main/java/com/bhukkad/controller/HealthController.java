package com.bhukkad.controller;

import com.bhukkad.cache.RedisCacheService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final DataSource dataSource;
    private final Environment environment;
    private final Instant startTime = Instant.now();

    @Value("${spring.application.name:bhukkad-server}")
    private String appName;

    @Value("${server.port:8080}")
    private String port;

    // Read from profile-specific yml
    @Value("${app.environment:not-set}")
    private String appEnvironment;

    @Value("${app.debug:false}")
    private boolean debugMode;

    // Add Redis dependency
    private final RedisCacheService cacheService;

    public HealthController(DataSource dataSource, Environment environment, RedisCacheService cacheService) {
        this.dataSource = dataSource;
        this.environment = environment;
        this.cacheService = cacheService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("application", appName);
        health.put("environment", appEnvironment);
        health.put("activeProfiles", getActiveProfiles());
        health.put("port", port);
        health.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        health.put("uptime", getUptime());
        return ResponseEntity.ok(health);
    }

    @GetMapping("/detailed")
    public ResponseEntity<Map<String, Object>> detailedHealthCheck() {
        Map<String, Object> health = new LinkedHashMap<>();

        health.put("status", "UP");
        health.put("application", appName);
        health.put("environment", appEnvironment);
        health.put("activeProfiles", getActiveProfiles());
        health.put("port", port);
        health.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        health.put("uptime", getUptime());
        health.put("database", checkDatabase());
        health.put("redis", checkRedis());     // ADD THIS
        health.put("memory", getMemoryInfo());
        health.put("jvm", getJvmInfo());
        health.put("system", getSystemInfo());

        return ResponseEntity.ok(health);
    }

    // Add Redis health method
    private Map<String, Object> checkRedis() {
        Map<String, Object> redisHealth = new LinkedHashMap<>();
        long startMs = System.currentTimeMillis();
        try {
            cacheService.set("health-ping", "pong", 10);
            var result = cacheService.get("health-ping", String.class);
            long duration = System.currentTimeMillis() - startMs;

            redisHealth.put("status", result.isPresent() ? "UP" : "DOWN");
            redisHealth.put("responseTimeMs", duration);
            redisHealth.put("stats", cacheService.getCacheStats());
            cacheService.delete("health-ping");
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startMs;
            redisHealth.put("status", "DOWN");
            redisHealth.put("error", e.getMessage());
            redisHealth.put("responseTimeMs", duration);
        }
        return redisHealth;
    }

    @GetMapping("/db")
    public ResponseEntity<Map<String, Object>> databaseHealth() {
        return ResponseEntity.ok(checkDatabase());
    }

    @GetMapping("/memory")
    public ResponseEntity<Map<String, Object>> memoryHealth() {
        return ResponseEntity.ok(getMemoryInfo());
    }

    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", "pong");
        response.put("application", appName);
        response.put("environment", appEnvironment);
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/env")
    public ResponseEntity<Map<String, Object>> environmentInfo() {
        Map<String, Object> envInfo = new LinkedHashMap<>();
        envInfo.put("application", appName);
        envInfo.put("environment", appEnvironment);
        envInfo.put("activeProfiles", getActiveProfiles());
        envInfo.put("debugMode", debugMode);
        envInfo.put("port", port);
        envInfo.put("javaVersion", System.getProperty("java.version"));
        envInfo.put("os", System.getProperty("os.name"));
        envInfo.put("timezone", System.getProperty("user.timezone"));
        return ResponseEntity.ok(envInfo);
    }

    // ==================== PRIVATE METHODS ====================

    private String[] getActiveProfiles() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length > 0 ? profiles : new String[]{"default"};
    }

    private Map<String, Object> checkDatabase() {
        Map<String, Object> dbHealth = new LinkedHashMap<>();
        long startMs = System.currentTimeMillis();

        try (Connection connection = dataSource.getConnection()) {
            boolean isValid = connection.isValid(5);
            long duration = System.currentTimeMillis() - startMs;

            dbHealth.put("status", isValid ? "UP" : "DOWN");
            dbHealth.put("database", connection.getMetaData().getDatabaseProductName());
            dbHealth.put("version", connection.getMetaData().getDatabaseProductVersion());
            dbHealth.put("url", connection.getMetaData().getURL());
            dbHealth.put("responseTimeMs", duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startMs;
            dbHealth.put("status", "DOWN");
            dbHealth.put("error", e.getMessage());
            dbHealth.put("responseTimeMs", duration);
        }

        return dbHealth;
    }

    private Map<String, Object> getMemoryInfo() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        Runtime runtime = Runtime.getRuntime();

        Map<String, Object> memory = new LinkedHashMap<>();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        memory.put("heapUsed", formatBytes(usedMemory));
        memory.put("heapFree", formatBytes(freeMemory));
        memory.put("heapTotal", formatBytes(totalMemory));
        memory.put("heapMax", formatBytes(maxMemory));
        memory.put("heapUsagePercent", String.format("%.2f%%", (double) usedMemory / maxMemory * 100));
        memory.put("nonHeapUsed", formatBytes(memoryBean.getNonHeapMemoryUsage().getUsed()));

        double usagePercent = (double) usedMemory / maxMemory * 100;
        if (usagePercent > 90) {
            memory.put("status", "CRITICAL");
        } else if (usagePercent > 75) {
            memory.put("status", "WARNING");
        } else {
            memory.put("status", "HEALTHY");
        }

        return memory;
    }

    private Map<String, Object> getJvmInfo() {
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> jvm = new LinkedHashMap<>();

        jvm.put("javaVersion", System.getProperty("java.version"));
        jvm.put("javaVendor", System.getProperty("java.vendor"));
        jvm.put("jvmName", runtimeBean.getVmName());
        jvm.put("jvmVersion", runtimeBean.getVmVersion());
        jvm.put("pid", runtimeBean.getPid());
        jvm.put("startTime", Instant.ofEpochMilli(runtimeBean.getStartTime()).toString());
        jvm.put("uptime", formatDuration(Duration.ofMillis(runtimeBean.getUptime())));
        jvm.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        jvm.put("activeThreads", Thread.activeCount());

        return jvm;
    }

    private Map<String, Object> getSystemInfo() {
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("os", System.getProperty("os.name"));
        system.put("osVersion", System.getProperty("os.version"));
        system.put("arch", System.getProperty("os.arch"));
        system.put("userTimezone", System.getProperty("user.timezone"));
        system.put("fileEncoding", System.getProperty("file.encoding"));
        return system;
    }

    private String getUptime() {
        return formatDuration(Duration.between(startTime, Instant.now()));
    }

    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");

        return sb.toString().trim();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }
}