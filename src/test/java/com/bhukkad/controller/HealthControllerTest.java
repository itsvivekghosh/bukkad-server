package com.bhukkad.controller;

import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.cluster.InstanceMetadata;
import com.bhukkad.datasource.ReadReplicaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@Tag("regression")
@ExtendWith(MockitoExtension.class)
public class HealthControllerTest {

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

    private HealthController healthController;

    @BeforeEach
    void setUp() {
        lenient().when(instanceMetadata.getInstanceId()).thenReturn("test-instance");
        healthController = new HealthController(
                writeDataSource, readDataSource, readReplicaProperties, environment, cacheService, instanceMetadata);
        ReflectionTestUtils.setField(healthController, "appName", "bhukkad-server");
        ReflectionTestUtils.setField(healthController, "port", "8080");
        ReflectionTestUtils.setField(healthController, "appEnvironment", "test");
        ReflectionTestUtils.setField(healthController, "debugMode", true);
    }

    @Test
    void healthCheck_usesDefaultProfileWhenNoneActive() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        ResponseEntity<Map<String, Object>> response = healthController.healthCheck();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("bhukkad-server", response.getBody().get("application"));
        assertEquals("test", response.getBody().get("environment"));
        assertEquals("8080", response.getBody().get("port"));
        assertArrayEquals(new String[]{"default"}, (String[]) response.getBody().get("activeProfiles"));
        assertNotNull(response.getBody().get("uptime"));
        assertNotNull(response.getBody().get("timestamp"));
    }

    @Test
    void healthCheck_usesActiveProfilesWhenPresent() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev", "local"});

        ResponseEntity<Map<String, Object>> response = healthController.healthCheck();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(new String[]{"dev", "local"}, (String[]) response.getBody().get("activeProfiles"));
    }

    @Test
    void detailedHealthCheck_includesDatabaseRedisMemoryJvmAndSystem() throws Exception {
        stubDatabase(true);
        when(readReplicaProperties.isConfigured()).thenReturn(false);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(cacheService.get("health-ping", String.class)).thenReturn(Optional.of("pong"));
        when(cacheService.getCacheStats()).thenReturn(Map.of("totalKeys", 1));

        ResponseEntity<Map<String, Object>> response = healthController.detailedHealthCheck();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> db = (Map<String, Object>) response.getBody().get("database");
        assertEquals("UP", db.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> redis = (Map<String, Object>) response.getBody().get("redis");
        assertEquals("UP", redis.get("status"));
        assertNotNull(response.getBody().get("memory"));
        assertNotNull(response.getBody().get("jvm"));
        assertNotNull(response.getBody().get("system"));
        verify(cacheService).delete("health-ping");
    }

    @Test
    void detailedHealthCheck_marksRedisDownWhenGetEmpty() throws Exception {
        stubDatabase(true);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(cacheService.get("health-ping", String.class)).thenReturn(Optional.empty());
        when(cacheService.getCacheStats()).thenReturn(Map.of("totalKeys", 0));

        ResponseEntity<Map<String, Object>> response = healthController.detailedHealthCheck();

        @SuppressWarnings("unchecked")
        Map<String, Object> redis = (Map<String, Object>) response.getBody().get("redis");
        assertEquals("DOWN", redis.get("status"));
    }

    @Test
    void detailedHealthCheck_marksRedisDownWhenSetThrows() throws Exception {
        stubDatabase(true);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        doThrow(new RuntimeException("redis down")).when(cacheService).set("health-ping", "pong", 10);

        ResponseEntity<Map<String, Object>> response = healthController.detailedHealthCheck();

        @SuppressWarnings("unchecked")
        Map<String, Object> redis = (Map<String, Object>) response.getBody().get("redis");
        assertEquals("DOWN", redis.get("status"));
        assertEquals("redis down", redis.get("error"));
    }

    @Test
    void databaseHealth_reportsUpWhenConnectionValid() throws Exception {
        stubDatabase(true);

        ResponseEntity<Map<String, Object>> response = healthController.databaseHealth();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("MySQL", response.getBody().get("database"));
        assertEquals("8.0", response.getBody().get("version"));
        assertEquals("jdbc:mysql://localhost/bhukkad", response.getBody().get("url"));
        assertNotNull(response.getBody().get("responseTimeMs"));
    }

    @Test
    void databaseHealth_reportsDownWhenConnectionInvalid() throws Exception {
        stubDatabase(false);

        ResponseEntity<Map<String, Object>> response = healthController.databaseHealth();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("DOWN", response.getBody().get("status"));
    }

    @Test
    void databaseHealth_reportsDownWhenGetConnectionThrows() throws Exception {
        when(writeDataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        ResponseEntity<Map<String, Object>> response = healthController.databaseHealth();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals("connection refused", response.getBody().get("error"));
        assertNotNull(response.getBody().get("responseTimeMs"));
    }

    @Test
    void memoryHealth_returnsHealthyPath() {
        ResponseEntity<Map<String, Object>> response = healthController.memoryHealth();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("HEALTHY", response.getBody().get("status"));
        assertNotNull(response.getBody().get("heapUsed"));
        assertNotNull(response.getBody().get("heapFree"));
        assertNotNull(response.getBody().get("heapTotal"));
        assertNotNull(response.getBody().get("heapMax"));
        assertNotNull(response.getBody().get("heapUsagePercent"));
        assertNotNull(response.getBody().get("nonHeapUsed"));
    }

    @Test
    void ping_returnsPong() {
        ResponseEntity<Map<String, String>> response = healthController.ping();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("pong", response.getBody().get("status"));
        assertEquals("bhukkad-server", response.getBody().get("application"));
        assertEquals("test", response.getBody().get("environment"));
        assertNotNull(response.getBody().get("timestamp"));
    }

    @Test
    void environmentInfo_includesDebugModeAndProfiles() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        ResponseEntity<Map<String, Object>> response = healthController.environmentInfo();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("bhukkad-server", response.getBody().get("application"));
        assertEquals("test", response.getBody().get("environment"));
        assertArrayEquals(new String[]{"prod"}, (String[]) response.getBody().get("activeProfiles"));
        assertEquals(true, response.getBody().get("debugMode"));
        assertEquals("8080", response.getBody().get("port"));
        assertNotNull(response.getBody().get("javaVersion"));
        assertNotNull(response.getBody().get("os"));
        assertNotNull(response.getBody().get("timezone"));
    }

    @Test
    void formatDuration_includesDaysHoursMinutesAndSeconds() {
        Duration duration = Duration.ofDays(1).plusHours(2).plusMinutes(3).plusSeconds(4);

        String formatted = ReflectionTestUtils.invokeMethod(healthController, "formatDuration", duration);

        assertEquals("1d 2h 3m 4s", formatted);
    }

    @Test
    void formatDuration_secondsOnly() {
        String formatted = ReflectionTestUtils.invokeMethod(
                healthController, "formatDuration", Duration.ofSeconds(5));

        assertEquals("5s", formatted);
    }

    @Test
    void formatBytes_formatsBytesKilobytesAndMegabytes() {
        assertEquals("500 B", ReflectionTestUtils.invokeMethod(healthController, "formatBytes", 500L));
        assertEquals("1.00 KB", ReflectionTestUtils.invokeMethod(healthController, "formatBytes", 1024L));
        assertEquals("1.00 MB", ReflectionTestUtils.invokeMethod(healthController, "formatBytes", 1048576L));
    }

    @Test
    void getMemoryInfo_returnsHealthyStatus() {
        @SuppressWarnings("unchecked")
        Map<String, Object> memory = ReflectionTestUtils.invokeMethod(healthController, "getMemoryInfo");

        assertEquals("HEALTHY", memory.get("status"));
    }

    @Test
    void resolveMemoryStatus_coversAllThresholds() {
        assertEquals("HEALTHY", healthController.resolveMemoryStatus(75.0));
        assertEquals("WARNING", healthController.resolveMemoryStatus(75.1));
        assertEquals("WARNING", healthController.resolveMemoryStatus(90.0));
        assertEquals("CRITICAL", healthController.resolveMemoryStatus(90.1));
    }

    private void stubDatabase(boolean valid) throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(writeDataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(valid);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(metaData.getURL()).thenReturn("jdbc:mysql://localhost/bhukkad");
    }
}
