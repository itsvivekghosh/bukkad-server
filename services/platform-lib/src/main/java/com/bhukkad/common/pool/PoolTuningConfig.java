package com.bhukkad.common.pool;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link PoolTuningProperties} so the centralized pool-tuning
 * defaults are available as {@code app.pool.*} properties.
 *
 * <p>Individual services opt in by adding this config class to their
 * component scan or by importing it. The properties are documented in
 * {@link PoolTuningProperties} and are overridable per-service via
 * environment variables (e.g. {@code ORDER_DB_POOL_SIZE},
 * {@code DB_POOL_SIZE}).</p>
 *
 * <p>Observability: HikariCP metrics are exported via
 * {@code PrometheusMetricsTracker} when configured in application-prod.yml;
 * lettuce pool metrics are enabled via {@code management.metrics.enable.lettuce=true};
 * Kafka consumer lag is exported via the Kafka client metrics.</p>
 */
@Configuration
@EnableConfigurationProperties(PoolTuningProperties.class)
public class PoolTuningConfig {
}
