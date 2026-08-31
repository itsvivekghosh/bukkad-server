package com.bhukkad.common.integration;

import com.bhukkad.common.AbstractPostgresIntegrationTest;
import com.bhukkad.common.saga.SagaInstance;
import com.bhukkad.common.saga.SagaInstanceRepository;
import com.bhukkad.common.saga.SagaStep;
import com.bhukkad.common.saga.SagaStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates saga persistence on PostgreSQL: JSONB/TEXT payload round-trip,
 * unique saga_id, FK cascade, and step lookup.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = com.bhukkad.common.PlatformTestConfig.class)
class SagaPostgresIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SagaInstanceRepository instanceRepository;

    @Autowired
    private SagaStepRepository stepRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM saga_instances");
    }

    @Test
    void savesInstanceAndRoundTripsPayload() {
        SagaInstance saga = instanceRepository.saveAndFlush(
                SagaInstance.start("ORDER_CREATION", "saga-1", "{\"orderId\":7}"));

        SagaInstance readBack = instanceRepository.findBySagaId("saga-1");
        assertThat(readBack.getId()).isEqualTo(saga.getId());
        assertThat(readBack.getPayload()).isEqualTo("{\"orderId\":7}");
        assertThat(readBack.getStatus()).isEqualTo(SagaInstance.STATUS_STARTED);
    }

    @Test
    void persistsStepsWithCompensation() {
        SagaInstance saga = instanceRepository.saveAndFlush(
                SagaInstance.start("ORDER_CREATION", "saga-2", "{}"));
        SagaStep step = SagaStep.newStep(saga, 0, "RESERVE_INVENTORY", "{\"sku\":\"A\"}");
        step.markCompleted("{\"released\":true}");
        stepRepository.saveAndFlush(step);

        Optional<SagaStep> readBack = stepRepository.findBySagaInstanceIdAndStepOrder(saga.getId(), 0);
        assertThat(readBack).isPresent();
        assertThat(readBack.get().getStatus()).isEqualTo(SagaStep.STATUS_COMPLETED);
        assertThat(readBack.get().getCompensationPayload()).isEqualTo("{\"released\":true}");
    }

    @Test
    void duplicateSagaId_violatesUniqueConstraint() {
        instanceRepository.saveAndFlush(SagaInstance.start("ORDER_CREATION", "saga-unique", "{}"));

        assertThatThrownBy(() -> instanceRepository.saveAndFlush(
                SagaInstance.start("ORDER_CREATION", "saga-unique", "{}")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingInstance_cascadesToSteps() {
        SagaInstance saga = instanceRepository.saveAndFlush(
                SagaInstance.start("ORDER_CREATION", "saga-cascade", "{}"));
        stepRepository.saveAndFlush(SagaStep.newStep(saga, 0, "STEP", "{}"));

        int deleted = jdbcTemplate.update("DELETE FROM saga_instances WHERE id = ?", saga.getId());
        assertThat(deleted).isEqualTo(1);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM saga_steps WHERE saga_instance_id = ?", Integer.class, saga.getId());
        assertThat(remaining).isZero();
    }

    @Test
    void terminalStatus_isDetected() {
        SagaInstance completed = SagaInstance.start("ORDER_CREATION", "saga-terminal", "{}");
        completed.markCompleted();
        assertThat(completed.isTerminal()).isTrue();

        SagaInstance compensated = SagaInstance.start("ORDER_CREATION", "saga-comp", "{}");
        compensated.markCompensated();
        assertThat(compensated.isTerminal()).isTrue();

        SagaInstance inProgress = SagaInstance.start("ORDER_CREATION", "saga-progress", "{}");
        assertThat(inProgress.isTerminal()).isFalse();
    }
}
