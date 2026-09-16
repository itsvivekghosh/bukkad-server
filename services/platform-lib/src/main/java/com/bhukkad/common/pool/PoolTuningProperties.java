package com.bhukkad.common.pool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralized pool-tuning properties with production-validated defaults.
 *
 * <p>Values are overridable per-service via environment variables so ops can
 * tune without rebuilding. The defaults are conservative baselines for a
 * 3-replica, 2vCPU/4GiB pod; scale them using the metrics exported by
 * {@code PrometheusMetricsTracker} (HikariCP), lettuce pool metrics, and
 * Kafka consumer lag.</p>
 *
 * <p>Tuning guidance:
 * <ul>
 *   <li>HikariCP: start at 2×CPU cores + observed latency slope; watch
 *       {@code hikaricpu_pending_threads} and
 *       {@code hikaricpu_connection_acquire_milliseconds}.</li>
 *   <li>Lettuce: max-active should be ≤ (Redis maxclients - headroom). Watch
 *       {@code lettuce_connections_active}.</li>
 *   <li>Kafka consumer: increase {@code max-poll-records} only after
 *       {@code max-poll-interval-ms} and consumer lag stabilize; watch
 *       {@code kafka_consumer_group_lag}.</li>
 * </ul>
 *
 * <p>Per-service overrides (env vars):
 * <ul>
 *   <li>{@code ORDER_DB_POOL_SIZE} — order service HikariCP max pool</li>
 *   <li>{@code RESTAURANT_DB_POOL_SIZE} — restaurant service HikariCP max pool</li>
 *   <li>{@code DELIVERY_DB_POOL_SIZE} — delivery service HikariCP max pool</li>
 *   <li>{@code DB_POOL_SIZE} — default for all other services</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.pool")
public class PoolTuningProperties {

    /**
     * HikariCP maximum pool size for services with moderate DB load
     * (identity, payment, notification, support, survey, referral).
     * Override per-service with {@code DB_POOL_SIZE}.
     */
    private int hikariDefaultMaxPool = 20;

    /**
     * HikariCP maximum pool size for high-DB-load services (order, restaurant,
     * delivery). Override per-service with {@code ORDER_DB_POOL_SIZE} /
     * {@code RESTAURANT_DB_POOL_SIZE} / {@code DELIVERY_DB_POOL_SIZE}.
     */
    private int hikariHighLoadMaxPool = 30;

    /**
     * HikariCP minimum idle connections. Keeping this equal to max-pool-size
     * avoids pool warm-up latency under bursty load; set lower if connections
     * are expensive.
     */
    private int hikariMinIdle = 20;

    /**
     * HikariCP connection acquisition timeout (ms). If pending threads
     * spike, increase the pool rather than this value.
     */
    private int hikariConnectionTimeoutMs = 3000;

    /**
     * HikariCP keepalive interval (ms). Sends lightweight keepalive to
     * detect stale connections; 5 min is a safe default.
     */
    private long hikariKeepaliveTimeMs = 300000;

    /**
     * HikariCP maximum connection lifetime (ms). Shorter than DB/server
     * timeout to avoid stale connections; 15 min is a safe default.
     */
    private long hikariMaxLifetimeMs = 900000;

    /**
     * Lettuce pool max-active connections per service.
     * Redis default maxclients is 10000; leave headroom for other services.
     */
    private int lettuceMaxActive = 64;

    /**
     * Lettuce pool minimum idle connections.
     */
    private int lettuceMinIdle = 8;

    /**
     * Lettuce pool maximum idle connections.
     */
    private int lettuceMaxIdle = 32;

    /**
     * Lettuce pool acquire timeout.
     */
    private String lettuceMaxWait = "5s";

    /**
     * Kafka consumer max-poll-records. Increase after validating consumer
     * lag and processing time; higher values increase throughput but also
     * increase poll-interval risk.
     */
    private int kafkaMaxPollRecords = 500;

    /**
     * Kafka consumer session timeout (ms).
     */
    private int kafkaSessionTimeoutMs = 30000;

    /**
     * Kafka consumer heartbeat interval (ms). Must be &lt; session-timeout.
     */
    private int kafkaHeartbeatIntervalMs = 10000;

    /**
     * Kafka consumer max poll interval (ms). Must be longer than the
     * slowest expected processing batch.
     */
    private int kafkaMaxPollIntervalMs = 300000;

    /**
     * WebFlux/Netty worker thread count. 0 = auto = available processors.
     * For gateway/realtime, consider increasing if CPU-bound handlers
     * saturate under load.
     */
    private int nettyWorkerCount = 0;

    /**
     * Gateway HTTP client pool max in-flight connections per route.
     * Prevents fan-out storms from pinning gateway connections.
     */
    private int gatewayHttpClientMaxConnections = 2000;

    /**
     * Gateway HTTP client connection acquire timeout (ms).
     */
    private int gatewayHttpClientAcquireTimeoutMs = 8000;

    public int getHikariDefaultMaxPool() {
        return hikariDefaultMaxPool;
    }

    public void setHikariDefaultMaxPool(int hikariDefaultMaxPool) {
        this.hikariDefaultMaxPool = hikariDefaultMaxPool;
    }

    public int getHikariHighLoadMaxPool() {
        return hikariHighLoadMaxPool;
    }

    public void setHikariHighLoadMaxPool(int hikariHighLoadMaxPool) {
        this.hikariHighLoadMaxPool = hikariHighLoadMaxPool;
    }

    public int getHikariMinIdle() {
        return hikariMinIdle;
    }

    public void setHikariMinIdle(int hikariMinIdle) {
        this.hikariMinIdle = hikariMinIdle;
    }

    public int getHikariConnectionTimeoutMs() {
        return hikariConnectionTimeoutMs;
    }

    public void setHikariConnectionTimeoutMs(int hikariConnectionTimeoutMs) {
        this.hikariConnectionTimeoutMs = hikariConnectionTimeoutMs;
    }

    public long getHikariKeepaliveTimeMs() {
        return hikariKeepaliveTimeMs;
    }

    public void setHikariKeepaliveTimeMs(long hikariKeepaliveTimeMs) {
        this.hikariKeepaliveTimeMs = hikariKeepaliveTimeMs;
    }

    public long getHikariMaxLifetimeMs() {
        return hikariMaxLifetimeMs;
    }

    public void setHikariMaxLifetimeMs(long hikariMaxLifetimeMs) {
        this.hikariMaxLifetimeMs = hikariMaxLifetimeMs;
    }

    public int getLettuceMaxActive() {
        return lettuceMaxActive;
    }

    public void setLettuceMaxActive(int lettuceMaxActive) {
        this.lettuceMaxActive = lettuceMaxActive;
    }

    public int getLettuceMinIdle() {
        return lettuceMinIdle;
    }

    public void setLettuceMinIdle(int lettuceMinIdle) {
        this.lettuceMinIdle = lettuceMinIdle;
    }

    public int getLettuceMaxIdle() {
        return lettuceMaxIdle;
    }

    public void setLettuceMaxIdle(int lettuceMaxIdle) {
        this.lettuceMaxIdle = lettuceMaxIdle;
    }

    public String getLettuceMaxWait() {
        return lettuceMaxWait;
    }

    public void setLettuceMaxWait(String lettuceMaxWait) {
        this.lettuceMaxWait = lettuceMaxWait;
    }

    public int getKafkaMaxPollRecords() {
        return kafkaMaxPollRecords;
    }

    public void setKafkaMaxPollRecords(int kafkaMaxPollRecords) {
        this.kafkaMaxPollRecords = kafkaMaxPollRecords;
    }

    public int getKafkaSessionTimeoutMs() {
        return kafkaSessionTimeoutMs;
    }

    public void setKafkaSessionTimeoutMs(int kafkaSessionTimeoutMs) {
        this.kafkaSessionTimeoutMs = kafkaSessionTimeoutMs;
    }

    public int getKafkaHeartbeatIntervalMs() {
        return kafkaHeartbeatIntervalMs;
    }

    public void setKafkaHeartbeatIntervalMs(int kafkaHeartbeatIntervalMs) {
        this.kafkaHeartbeatIntervalMs = kafkaHeartbeatIntervalMs;
    }

    public int getKafkaMaxPollIntervalMs() {
        return kafkaMaxPollIntervalMs;
    }

    public void setKafkaMaxPollIntervalMs(int kafkaMaxPollIntervalMs) {
        this.kafkaMaxPollIntervalMs = kafkaMaxPollIntervalMs;
    }

    public int getNettyWorkerCount() {
        return nettyWorkerCount;
    }

    public void setNettyWorkerCount(int nettyWorkerCount) {
        this.nettyWorkerCount = nettyWorkerCount;
    }

    public int getGatewayHttpClientMaxConnections() {
        return gatewayHttpClientMaxConnections;
    }

    public void setGatewayHttpClientMaxConnections(int gatewayHttpClientMaxConnections) {
        this.gatewayHttpClientMaxConnections = gatewayHttpClientMaxConnections;
    }

    public int getGatewayHttpClientAcquireTimeoutMs() {
        return gatewayHttpClientAcquireTimeoutMs;
    }

    public void setGatewayHttpClientAcquireTimeoutMs(int gatewayHttpClientAcquireTimeoutMs) {
        this.gatewayHttpClientAcquireTimeoutMs = gatewayHttpClientAcquireTimeoutMs;
    }
}
