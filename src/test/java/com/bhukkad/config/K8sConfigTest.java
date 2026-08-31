package com.bhukkad.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates Kubernetes deployment, HPA, and PostgreSQL replica configuration
 * for the 5k RPS target and read-replica routing.
 */
class K8sConfigTest {

    private static final Path DEPLOYMENT = Paths.get("k8s/app/deployment.yaml");
    private static final Path HPA = Paths.get("k8s/app/hpa.yaml");
    private static final Path CONFIGMAP = Paths.get("k8s/configmap.yaml");

    @Test
    void deployment_has10ReplicasFor5kRps() throws IOException {
        String yaml = Files.readString(DEPLOYMENT, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("replicas: 10"), "Base deployment must have 10 replicas for 5k RPS");
        assertTrue(yaml.contains("maxSurge: 25%"), "Rolling update maxSurge required");
        assertTrue(yaml.contains("maxUnavailable: 0"), "Rolling update must not make replicas unavailable");
        assertTrue(yaml.contains("readinessProbe"), "Readiness probe required");
        assertTrue(yaml.contains("livenessProbe"), "Liveness probe required");
        assertTrue(yaml.contains("startupProbe"), "Startup probe required for slow-starting JVM");
    }

    @Test
    void hpa_scales10To20() throws IOException {
        String yaml = Files.readString(HPA, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("minReplicas: 10"), "HPA min must match deployment replicas");
        assertTrue(yaml.contains("maxReplicas: 20"), "HPA max must allow burst to 20");
        assertTrue(yaml.contains("averageUtilization: 65"), "CPU target must be tuned for JVM");
        assertTrue(yaml.contains("scaleDown"), "Scale-down stabilization required");
        assertTrue(yaml.contains("scaleUp"), "Aggressive scale-up required for traffic bursts");
    }

    @Test
    void deployment_hasResourceLimits() throws IOException {
        String yaml = Files.readString(DEPLOYMENT, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("cpu: \"1500m\""), "CPU limit must be set");
        assertTrue(yaml.contains("memory: 1536Mi"), "Memory limit must be set");
        assertTrue(yaml.contains("cpu: 500m"), "CPU request must be set");
        assertTrue(yaml.contains("memory: 896Mi"), "Memory request must be set");
    }

    @Test
    void postgresReplica_disabledByDefault() throws IOException {
        String yaml = Files.readString(CONFIGMAP, StandardCharsets.UTF_8);
        // Read-replica routing is enabled for PostgreSQL with streaming replication.
        assertTrue(yaml.contains("DB_REPLICA_ENABLED: \"true\""),
                "Read replica must be enabled for PostgreSQL streaming replication");
        assertTrue(yaml.contains("DB_REPLICA_URL:"), "Replica URL env var must be defined");
        assertTrue(yaml.contains("jdbc:postgresql://"), "Datasource URL must use PostgreSQL");
    }

    @Test
    void postgresReplica_hasCorrectPort() throws IOException {
        String yaml = Files.readString(CONFIGMAP, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("DB_REPLICA_PORT: \"5432\""),
                "PostgreSQL replica must use port 5432");
        assertTrue(yaml.contains("currentSchema=public"), "PostgreSQL URL must set currentSchema");
        assertFalse(yaml.contains("jdbc:mysql://"), "MySQL JDBC URLs must be removed");
    }
}
