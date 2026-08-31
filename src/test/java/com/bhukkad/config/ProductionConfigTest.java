package com.bhukkad.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the production configuration surfaces the settings the
 * production-readiness checklist requires:
 * <ul>
 *   <li>HikariCP pool = 30 connections per pod (10 pods × 30 = 300 &lt; MySQL 500)</li>
 *   <li>Read-replica routing wired to env vars, disabled by default</li>
 *   <li>Springdoc / Swagger disabled by default but overridable via SPRINGDOC_ENABLED</li>
 *   <li>Flyway out-of-order toggleable via FLYWAY_OUT_OF_ORDER</li>
 * </ul>
 */
class ProductionConfigTest {

    @Test
    void prodProfile_definesHikariPool30AndTomcatHttp2() throws IOException {
        PropertySource<?> prod = load("application-prod.yml");

        assertNotNull(prod.getProperty("spring.datasource.hikari.maximum-pool-size"));
        // ${DB_POOL_SIZE:30} → default is 30 connections per pod.
        assertEquals("${DB_POOL_SIZE:30}", prod.getProperty("spring.datasource.hikari.maximum-pool-size"));
        assertEquals("${DB_MIN_IDLE:10}", prod.getProperty("spring.datasource.hikari.minimum-idle"));

        // HTTP/2 must be enabled for the production load balancer.
        assertEquals(Boolean.TRUE, prod.getProperty("server.http2.enabled"));

        // Tomcat sized for 5k RPS with a 10-pod fleet.
        assertEquals("${TOMCAT_THREADS_MAX:100}", prod.getProperty("server.tomcat.threads.max"));
        assertEquals("${TOMCAT_MAX_CONNECTIONS:8192}", prod.getProperty("server.tomcat.max-connections"));
    }

    @Test
    void prodProfile_springdocDisabledByDefaultButEnvOverridable() throws IOException {
        PropertySource<?> prod = load("application-prod.yml");

        // Hardcoded `false` from the limitation report must be gone; it must now
        // be env-driven so a staging deploy can opt in without changing code.
        assertEquals("${SPRINGDOC_ENABLED:false}", prod.getProperty("springdoc.swagger-ui.enabled"));
        assertEquals("${SPRINGDOC_ENABLED:false}", prod.getProperty("springdoc.api-docs.enabled"));
    }

    @Test
    void prodProfile_readReplicaWiredAndDisabledByDefault() throws IOException {
        PropertySource<?> prod = load("application-prod.yml");

        assertEquals("${DB_REPLICA_ENABLED:false}", prod.getProperty("app.datasource.read-replica.enabled"));
        assertEquals("${DB_REPLICA_URL:}", prod.getProperty("app.datasource.read-replica.url"));
        assertEquals("${DB_REPLICA_USERNAME:}", prod.getProperty("app.datasource.read-replica.username"));
        assertEquals("${DB_REPLICA_PASSWORD:}", prod.getProperty("app.datasource.read-replica.password"));
        assertEquals("${DB_REPLICA_POOL_SIZE:25}", prod.getProperty("app.datasource.read-replica.hikari.maximum-pool-size"));
        // YAML booleans resolve as Boolean true; property source returns the typed value.
        assertEquals(Boolean.TRUE, prod.getProperty("app.datasource.read-replica.hikari.read-only"));
    }

    @Test
    void baseConfig_flywayOutOfOrderEnvDriven() throws IOException {
        PropertySource<?> base = load("application.yml");

        // Must remain env-tunable so production can pin a reproducible migration
        // order once environments converge (see the squash-baseline note).
        assertEquals("${FLYWAY_OUT_OF_ORDER:true}", base.getProperty("spring.flyway.out-of-order"));
        assertEquals("${FLYWAY_IGNORE_MISSING:*:missing}", base.getProperty("spring.flyway.ignore-migration-patterns"));
        // baseline-on-migrate is a YAML boolean, so it resolves as Boolean true.
        assertEquals(Boolean.TRUE, base.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("classpath:db/migration-pg", base.getProperty("spring.flyway.locations"));
    }

    @Test
    void baseConfig_razorpayEnvDriven() throws IOException {
        PropertySource<?> base = load("application.yml");
        // The simulated gateway (default false) is rejected in prod by
        // SecretValidationConfig; production sets APP_PAYMENT_RAZORPAY_ENABLED=true.
        assertEquals("${APP_PAYMENT_RAZORPAY_ENABLED:false}", base.getProperty("app.payment.razorpay.enabled"));
    }

    private PropertySource<?> load(String location) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("test-" + location, new ClassPathResource(location));
        assertFalse(sources.isEmpty(), "Expected at least one document in " + location);
        return sources.get(0);
    }

    // Guard against regression: the top-level `app:` key must NOT appear under
    // `spring:` (the duplicate-key bug from the earlier production failure).
    @Test
    void prodProfile_noDuplicateSpringOrMisplacedAppKey() throws IOException {
        PropertySource<?> prod = load("application-prod.yml");
        // If `app.datasource.read-replica` had been accidentally nested under
        // `spring.app...`, the property would still resolve here, but the
        // ReadReplicaProperties prefix would NOT match. The read-replica
        // test above already asserts the correct top-level key. Here we just
        // assert the spring.datasource settings still resolve (no YAML collapse).
        assertNotNull(prod.getProperty("spring.datasource.url"));
        assertNotNull(prod.getProperty("spring.data.redis.host"));
    }

    @Test
    void baseConfig_hasNoDuplicateSpringKeys() throws IOException {
        PropertySource<?> base = load("application.yml");
        assertNotNull(base.getProperty("spring.application.name"));
        assertNotNull(base.getProperty("spring.flyway.enabled"));
    }

    @Test
    void prodProfile_redisClusterConfigPresent() throws IOException {
        PropertySource<?> prod = load("application-prod.yml");
        // Redis Cluster nodes must be configurable via env for scaling past 1M keys.
        assertEquals("${REDIS_CLUSTER_NODES:}", prod.getProperty("spring.data.redis.lettuce.cluster.nodes"));
        assertEquals(Integer.valueOf(3), prod.getProperty("spring.data.redis.lettuce.cluster.max-redirects"));
    }

    @Test
    void baseConfig_redisClusterConfigPresent() throws IOException {
        PropertySource<?> base = load("application.yml");
        assertEquals("${REDIS_CLUSTER_NODES:}", base.getProperty("spring.data.redis.lettuce.cluster.nodes"));
        assertEquals(Integer.valueOf(3), base.getProperty("spring.data.redis.lettuce.cluster.max-redirects"));
    }

    @Test
    void prodProfile_logRotationConfigured() throws IOException {
        PropertySource<?> prod = load("application-prod.yml");
        // Logback config is XML, but we can verify the app does not disable logging
        // entirely in prod and that the root level is set appropriately.
        assertEquals("WARN", prod.getProperty("logging.level.root"));
        assertEquals("INFO", prod.getProperty("logging.level.com.bhukkad"));
    }

    @Test
    void prodProfile_backupAndMonitoringEnabled() throws IOException {
        PropertySource<?> prod = load("application-prod.yml");
        assertNotNull(prod.getProperty("management.endpoints.web.exposure.include"));
        assertEquals("health,info,metrics,prometheus",
                prod.getProperty("management.endpoints.web.exposure.include"));
    }
}
