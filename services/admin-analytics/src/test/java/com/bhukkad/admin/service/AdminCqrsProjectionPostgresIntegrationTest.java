package com.bhukkad.admin.service;
import com.bhukkad.admin.infrastructure.messaging.AdminCqrsEventConsumer;

import com.bhukkad.admin.AbstractAdminPostgresTest;
import com.bhukkad.admin.domain.repository.RestaurantOrderStatRepository;
import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V-12 verification against real PostgreSQL:
 *
 * <ol>
 *   <li>2 threads × 4 {@code OrderCreated} events for the SAME restaurant
 *       land exactly {@code order_count == 8} and
 *       {@code revenue == 8 × 10.00} — the atomic upsert has no lost
 *       updates (the old findById→+1→save path could not promise this and the
 *       test fails on it).</li>
 *   <li>The SAME eventId delivered twice projects exactly once — the in-tx
 *       {@code ADMIN_PROJECTION} idempotency claim absorbs redelivery.</li>
 * </ol>
 *
 * <p>Runs with the test transaction suspended ({@code NOT_SUPPORTED}) so each
 * event commits through its own transaction — the visibility + race semantics
 * operators actually have.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminCqrsProjectionPostgresIntegrationTest extends AbstractAdminPostgresTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Autowired
    private RestaurantOrderStatRepository statRepository;
    @Autowired
    private IdempotencyRecordRepository idempotencyRecords;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AdminCqrsEventConsumer consumer;
    private TransactionTemplate tx;

    @BeforeEach
    void wire() {
        consumer = new AdminCqrsEventConsumer(statRepository, idempotencyRecords, objectMapper);
        tx = new TransactionTemplate(transactionManager);
        jdbcTemplate.update("DELETE FROM restaurant_order_stats");
        jdbcTemplate.update("DELETE FROM idempotency_records");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void concurrentProjections_twoThreadsFourEvents_sameRestaurant_exactlyEight() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < 2; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    awaitStart(start);
                    for (int i = 0; i < 4; i++) {
                        String payload = ("{\"orderId\":%d,\"customerId\":9,\"restaurantId\":77,"
                                + "\"totalAmount\":10.00}").formatted(thread * 100 + i);
                        PlatformEventMessage event = PlatformEventMessage.of(
                                "OrderCreated", String.valueOf(thread * 100 + i), payload);
                        String envelope = envelope(event);
                        // Real tx per event (listener + @Transactional semantics).
                        tx.executeWithoutResult(status -> consumer.onOrderEvent(envelope));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        Long count = jdbcTemplate.queryForObject(
                "SELECT order_count FROM restaurant_order_stats WHERE restaurant_id = 77", Long.class);
        BigDecimal revenue = jdbcTemplate.queryForObject(
                "SELECT revenue FROM restaurant_order_stats WHERE restaurant_id = 77", BigDecimal.class);
        assertThat(count).isEqualTo(8L);
        assertThat(revenue).isEqualByComparingTo("80.00");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void sameEventIdTwice_projectsExactlyOnce() {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42", "{\"restaurantId\":77,\"totalAmount\":5.00}");
        String envelope = envelope(event);

        tx.executeWithoutResult(status -> consumer.onOrderEvent(envelope));
        tx.executeWithoutResult(status -> consumer.onOrderEvent(envelope)); // redelivery

        Long count = jdbcTemplate.queryForObject(
                "SELECT order_count FROM restaurant_order_stats WHERE restaurant_id = 77", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    private String envelope(PlatformEventMessage event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
