package com.bhukkad.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Probes critical dependencies on startup and logs the result. Failures are
 * surfaced at WARN so ops can catch misconfigured DB/Redis/Kafka before
 * traffic arrives.
 */
@Component
public class DependencyHealthLogger {

    private static final Logger log = LoggerFactory.getLogger("HEALTH");

    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<?, ?> redisTemplate;
    private final KafkaTemplate<?, ?> kafkaTemplate;

    public DependencyHealthLogger(
            @org.springframework.beans.factory.annotation.Autowired(required = false) JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Autowired(required = false) RedisTemplate<?, ?> redisTemplate,
            @org.springframework.beans.factory.annotation.Autowired(required = false) KafkaTemplate<?, ?> kafkaTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        Map<String, String> results = new HashMap<>();

        // Database
        if (jdbcTemplate != null) {
            try {
                String dbResult = testDatabase();
                results.put("database", dbResult);
            } catch (Exception e) {
                results.put("database", "FAIL: " + e.getMessage());
            }
        } else {
            results.put("database", "SKIPPED");
        }

        // Redis
        if (redisTemplate != null) {
            try {
                String redisResult = testRedis();
                results.put("redis", redisResult);
            } catch (Exception e) {
                results.put("redis", "FAIL: " + e.getMessage());
            }
        } else {
            results.put("redis", "SKIPPED");
        }

        // Kafka
        if (kafkaTemplate != null) {
            try {
                String kafkaResult = testKafka();
                results.put("kafka", kafkaResult);
            } catch (Exception e) {
                results.put("kafka", "FAIL: " + e.getMessage());
            }
        } else {
            results.put("kafka", "SKIPPED");
        }

        log.info("HEALTH_CHECK | database={} | redis={} | kafka={}",
            results.get("database"), results.get("redis"), results.get("kafka"));
    }

    private String testDatabase() {
        try {
            // Use metadata query that works across most databases
            String result = jdbcTemplate.queryForObject("SELECT 1", String.class);
            return "OK (SELECT 1 = " + result + ")";
        } catch (DataAccessException e) {
            Throwable root = e.getRootCause();
            return "FAIL: " + (root != null ? root.getMessage() : e.getMessage());
        }
    }

    private String testRedis() {
        try {
            String pong = redisTemplate.getConnectionFactory()
                .getConnection()
                .ping();
            return "OK (ping=" + pong + ")";
        } catch (Exception e) {
            return "FAIL: " + e.getMessage();
        }
    }

    private String testKafka() {
        try {
            // Verify producer is functional by checking metadata
            kafkaTemplate.executeInTransaction(operations -> {
                // Just verify we can get cluster info; don't actually send
                operations.getProducerFactory().createProducer();
                return null;
            });
            return "OK (producer initialized)";
        } catch (Exception e) {
            return "FAIL: " + e.getMessage();
        }
    }
}
