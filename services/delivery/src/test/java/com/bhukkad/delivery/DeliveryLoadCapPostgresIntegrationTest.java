package com.bhukkad.delivery;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.delivery.service.DeliveryEventPublisher;
import com.bhukkad.delivery.service.DeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * PERF-4 rider-matching load cap on real PostgreSQL (migration V10): the
 * conditional {@code UPDATE ... SET active_load = active_load + 1
 * WHERE id = ? AND active_load < cap} is the single-winner arbiter — with
 * cap=1 two racing dispatchers can never both land on the same rider. The
 * per-agent counter is released exactly once by markDelivered. Concurrency
 * methods run {@code NOT_SUPPORTED} so worker threads commit real rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DeliveryService.class)
@TestPropertySource(properties = "app.delivery.active-load-cap=1")
class DeliveryLoadCapPostgresIntegrationTest extends AbstractDeliveryPostgresTest {

    @Autowired private DeliveryService deliveryService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private DeliveryEventPublisher eventPublisher;

    private final AtomicInteger assignedEvents = new AtomicInteger();

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM delivery_assignments");
        jdbcTemplate.update("DELETE FROM delivery_agents");
        assignedEvents.set(0);
        doAnswer(inv -> {
            assignedEvents.incrementAndGet();
            return null;
        }).when(eventPublisher).deliveryAssigned(anyLong(), anyLong());
    }

    private long seedAgent(String name) {
        jdbcTemplate.update(
                "INSERT INTO delivery_agents (name, is_active, active_load, created_at, updated_at) "
                        + "VALUES (?, true, 0, now(), now())", name);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM delivery_agents WHERE name = ?", Long.class, name);
    }

    private int activeLoad(long agentId) {
        return jdbcTemplate.queryForObject(
                "SELECT active_load FROM delivery_agents WHERE id = ?", Integer.class, agentId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void racingAssignsDifferentOrders_singleWinnerPerCappedAgent() throws Exception {
        long agentA = seedAgent("Cap A");
        long agentB = seedAgent("Cap B");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CopyOnWriteArrayList<Object> outcomes = new CopyOnWriteArrayList<>();
        for (long orderId : new long[]{800L, 801L}) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                    outcomes.add(deliveryService.assign(orderId).getAgentId());
                } catch (Throwable t) {
                    outcomes.add(t);
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Both orders assigned, each to a DIFFERENT rider: the cap=1 agent can
        // take at most one order — exactly one thread wins each admission.
        assertThat(outcomes).hasSize(2).doesNotContainNull();
        assertThat(outcomes).allMatch(o -> o instanceof Long);
        assertThat(outcomes.stream().map(o -> (Long) o).distinct().count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM delivery_assignments", Integer.class)).isEqualTo(2);
        assertThat(activeLoad(agentA)).isEqualTo(1);
        assertThat(activeLoad(agentB)).isEqualTo(1);
        assertThat(assignedEvents.get()).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void racingAssignsSameOrder_capLoserReportsAlreadyAssigned() throws Exception {
        seedAgent("Solo");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        CopyOnWriteArrayList<Throwable> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    deliveryService.assign(810L);
                    results.add(null);
                } catch (Throwable t) {
                    results.add(t);
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(t -> t != null).findFirst().orElseThrow())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already assigned");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM delivery_assignments WHERE order_id = 810", Integer.class))
                .isEqualTo(1);
        assertThat(assignedEvents.get()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void markDelivered_releasesTheLoadSlotExactlyOnce() {
        long agentId = seedAgent("Releasable");
        deliveryService.assign(820L);
        assertThat(activeLoad(agentId)).isEqualTo(1);

        assertThat(deliveryService.markDelivered(820L).getStatus()).isEqualTo("DELIVERED");
        assertThat(activeLoad(agentId)).isZero();

        // Idempotent replay must not push the counter below zero.
        deliveryService.markDelivered(820L);
        assertThat(activeLoad(agentId)).isZero();
    }
}
