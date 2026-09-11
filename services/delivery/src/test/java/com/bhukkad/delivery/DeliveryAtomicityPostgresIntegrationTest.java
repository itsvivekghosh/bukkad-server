package com.bhukkad.delivery;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.service.DeliveryEventPublisher;
import com.bhukkad.delivery.service.DeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
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
 * B10 atomicity on real PostgreSQL (migrations V1-V8): racing {@code assign()}
 * callers produce exactly one assignment (UNIQUE(order_id) + ON CONFLICT DO
 * NOTHING) and racing {@code markDelivered()} callers publish exactly one
 * OrderDelivered (conditional UPDATE). Concurrency methods run
 * {@code NOT_SUPPORTED} so the worker threads commit real rows invisible to no
 * per-test rollback.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// RiderProximityMatcher is required by DeliveryService's constructor; with
// app.delivery.geo-matching.enabled left false it always defers to the legacy
// findFirst pick exercised by these tests.
@Import({DeliveryService.class, com.bhukkad.delivery.service.RiderProximityMatcher.class})
class DeliveryAtomicityPostgresIntegrationTest extends AbstractDeliveryPostgresTest {

    @Autowired private DeliveryService deliveryService;
    @Autowired private DeliveryAgentRepository agentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private DeliveryEventPublisher eventPublisher;

    private final AtomicInteger assignedEvents = new AtomicInteger();
    private final AtomicInteger deliveredEvents = new AtomicInteger();

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM delivery_assignments");
        jdbcTemplate.update("DELETE FROM delivery_agents");
        assignedEvents.set(0);
        deliveredEvents.set(0);
        doAnswer(inv -> {
            assignedEvents.incrementAndGet();
            return null;
        }).when(eventPublisher).deliveryAssigned(anyLong(), anyLong());
        doAnswer(inv -> {
            deliveredEvents.incrementAndGet();
            return null;
        }).when(eventPublisher).orderDelivered(anyLong(), anyLong());
    }

    private void seedAgent() {
        DeliveryAgent a = new DeliveryAgent();
        a.setName("Rider Solo");
        a.setIsActive(true);
        // No ambient transaction on NOT_SUPPORTED methods → save() runs in its
        // own committed transaction and is visible to the worker threads.
        agentRepository.saveAndFlush(a);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void racingAssigns_produceExactlyOneWinner() throws Exception {
        seedAgent();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CopyOnWriteArrayList<Throwable> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                    deliveryService.assign(500L);
                    results.add(null);
                } catch (Throwable t) {
                    results.add(t);
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<Throwable> failures = results.stream().filter(t -> t != null).toList();
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("already assigned");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM delivery_assignments WHERE order_id = 500", Integer.class))
                .isEqualTo(1);
        assertThat(assignedEvents.get()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sequentialAssignAfterWinner_throwsAlreadyAssigned() {
        seedAgent();

        assertThat(deliveryService.assign(501L).getStatus()).isEqualTo(DeliveryAssignment.STATUS_ASSIGNED);

        assertThatThrownBy(() -> deliveryService.assign(501L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already assigned");
        assertThat(assignedEvents.get()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void racingMarkDelivered_publishesExactlyOneOrderDelivered() throws Exception {
        seedAgent();
        deliveryService.assign(600L);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        CopyOnWriteArrayList<Throwable> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    deliveryService.markDelivered(600L);
                    results.add(null);
                } catch (Throwable t) {
                    results.add(t);
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(results).containsOnlyNulls(); // idempotent no-op, never an error
        assertThat(deliveredEvents.get()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_assignments WHERE order_id = 600", String.class))
                .isEqualTo("DELIVERED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivered_at IS NOT NULL FROM delivery_assignments WHERE order_id = 600",
                Boolean.class)).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void markDelivered_repeated_isIdempotentNoOp() {
        seedAgent();
        deliveryService.assign(601L);

        assertThat(deliveryService.markDelivered(601L).getStatus()).isEqualTo(DeliveryAssignment.STATUS_DELIVERED);
        assertThat(deliveryService.markDelivered(601L).getStatus()).isEqualTo(DeliveryAssignment.STATUS_DELIVERED);

        assertThat(deliveredEvents.get()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void uniqueConstraintRejectsDuplicateOrderRows_directSql() {
        seedAgent();
        Long agentId = jdbcTemplate.queryForObject("SELECT id FROM delivery_agents LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO delivery_assignments "
                + "(order_id, agent_id, status, assigned_at, created_at) "
                + "VALUES (700, ?, 'ASSIGNED', now(), now())", agentId);

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO delivery_assignments "
                + "(order_id, agent_id, status, assigned_at, created_at) "
                + "VALUES (700, ?, 'ASSIGNED', now(), now())", agentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void v8Migration_addsConstraintAndRetentionIndexes() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_delivery_assignments_order'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_rider_location_recorded'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'shedlock'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void testTransactionBaseline_isRollbackManagedForPlainTests() {
        // Guard for the NOT_SUPPORTED reasoning above: plain @DataJpaTest
        // methods DO run inside a managed (rolling-back) transaction.
        assertThat(TestTransaction.isActive()).isTrue();
    }
}
