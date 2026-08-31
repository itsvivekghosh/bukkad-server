package com.bhukkad.integration;

import com.bhukkad.order.SagaEvent;
import com.bhukkad.order.SagaEventRepository;
import com.bhukkad.order.SagaStep;
import com.bhukkad.order.SagaStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates saga persistence against a real PostgreSQL 16 database: the
 * {@code saga_instances} / {@code saga_steps} tables from the PG baseline, the
 * JSONB payload columns, the unique {@code saga_id}, and the FK cascade
 * (architecture-microservices-postgresql.md §12 P0 — bhukkad-common saga is a
 * platform library every service owns locally).
 *
 * <p>This is the first saga integration test in the repo (previously saga was
 * only unit-tested) and the first to exercise the JSON → JSONB mapping against
 * a real database.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SagaPostgresIntegrationTest extends AbstractPostgresJpaIntegrationTest {

    private static final String ORDER_PAYLOAD = "{\"orderId\":7,\"customerId\":3,\"items\":[{\"id\":1,\"qty\":2}]}";
    private static final String COMPENSATION = "{\"orderId\":7,\"releasedStock\":true}";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SagaEventRepository sagaEventRepository;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM saga_instances");
    }

    @Test
    void savesSagaInstanceAndRoundTripsJsonbPayload() {
        SagaEvent saga = SagaEvent.start("ORDER_CREATION", "saga-001", ORDER_PAYLOAD);
        SagaEvent saved = sagaEventRepository.saveAndFlush(saga);

        SagaEvent readBack = sagaEventRepository.findBySagaId("saga-001");
        assertThat(readBack.getId()).isEqualTo(saved.getId());
        assertThat(readBack.getSagaType()).isEqualTo("ORDER_CREATION");
        assertThat(readBack.getStatus()).isEqualTo("STARTED");
        assertThat(readBack.getPayload()).isEqualTo(ORDER_PAYLOAD);
        assertThat(readBack.getCreatedAt()).isNotNull();
        assertThat(readBack.getUpdatedAt()).isNotNull();
    }

    @Test
    void persistsStepsWithPayloadAndCompensationJsonb() {
        SagaEvent saga = sagaEventRepository.saveAndFlush(SagaEvent.start("ORDER_CREATION", "saga-002", "{}"));

        SagaStep step0 = SagaStep.newStep(saga, 0, "RESERVE_INVENTORY", "{\"sku\":\"A\"}");
        step0.markCompleted(COMPENSATION);
        SagaStep step1 = SagaStep.newStep(saga, 1, "PROCESS_PAYMENT", "{\"amount\":1200}");
        step1.markCompleted("{}");
        sagaStepRepository.save(step0);
        sagaStepRepository.save(step1);
        sagaStepRepository.flush();

        Optional<SagaStep> step0Read = sagaStepRepository.findBySagaInstanceIdAndStepOrder(saga.getId(), 0);
        assertThat(step0Read).isPresent();
        assertThat(step0Read.get().getStepName()).isEqualTo("RESERVE_INVENTORY");
        assertThat(step0Read.get().getStatus()).isEqualTo("COMPLETED");
        assertThat(step0Read.get().getCompensationPayload()).isEqualTo(COMPENSATION);
        assertThat(step0Read.get().getPayload()).isEqualTo("{\"sku\":\"A\"}");

        Optional<SagaStep> step1Read = sagaStepRepository.findBySagaInstanceIdAndStepOrder(saga.getId(), 1);
        assertThat(step1Read).isPresent();
        assertThat(step1Read.get().getPayload()).isEqualTo("{\"amount\":1200}");
    }

    @Test
    void failedStep_persistsErrorMessage() {
        SagaEvent saga = sagaEventRepository.saveAndFlush(SagaEvent.start("ORDER_CREATION", "saga-003", "{}"));
        SagaStep step = SagaStep.newStep(saga, 0, "PROCESS_PAYMENT", "{}");
        step.markFailed("insufficient funds");
        sagaStepRepository.saveAndFlush(step);

        Optional<SagaStep> readBack = sagaStepRepository.findBySagaInstanceIdAndStepOrder(saga.getId(), 0);
        assertThat(readBack).isPresent();
        assertThat(readBack.get().getStatus()).isEqualTo("FAILED");
        assertThat(readBack.get().getErrorMessage()).isEqualTo("insufficient funds");
    }

    @Test
    void duplicateSagaId_violatesUniqueConstraint() {
        sagaEventRepository.saveAndFlush(SagaEvent.start("ORDER_CREATION", "saga-unique", "{}"));

        assertThatThrownBy(() ->
                sagaEventRepository.saveAndFlush(SagaEvent.start("ORDER_CREATION", "saga-unique", "{}")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingInstance_cascadesToSteps() {
        SagaEvent saga = sagaEventRepository.saveAndFlush(SagaEvent.start("ORDER_CREATION", "saga-cascade", "{}"));
        sagaStepRepository.saveAndFlush(SagaStep.newStep(saga, 0, "RESERVE_INVENTORY", "{}"));

        int deleted = jdbcTemplate.update("DELETE FROM saga_instances WHERE id = ?", saga.getId());
        assertThat(deleted).isEqualTo(1);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM saga_steps WHERE saga_instance_id = ?", Integer.class, saga.getId());
        assertThat(remaining).isZero();
    }

    @Test
    void stepsRequireExistingInstance() {
        SagaEvent detached = SagaEvent.start("ORDER_CREATION", "saga-detached", "{}");

        assertThatThrownBy(() -> sagaStepRepository.saveAndFlush(SagaStep.newStep(detached, 0, "STEP", "{}")))
                .isInstanceOf(Exception.class);
    }
}