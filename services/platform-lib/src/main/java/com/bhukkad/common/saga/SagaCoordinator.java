package com.bhukkad.common.saga;

import com.bhukkad.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Orchestrates a saga: executes steps in order, compensates completed steps in
 * reverse on failure (plan §6.4). Derived from the monolith's
 * {@code SagaCoordinator} (com.bhukkad.order) — the canonical platform version
 * for all microservices.
 *
 * <p>Replaying a terminal saga (COMPLETED or COMPENSATED) is a no-op, providing
 * idempotency under outbox/retry re-delivery. An in-progress saga with the same
 * id is rejected to prevent double-execution.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class SagaCoordinator {

    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;

    /**
     * Runs the saga. Returns the terminal {@link SagaInstance} (COMPLETED or
     * COMPENSATED). Throws {@link BusinessException} when the saga fails and
     * compensation could not be completed, or when a saga with the same id is
     * already in progress.
     */
    @Transactional
    public SagaInstance executeSaga(String sagaType, String sagaId, String payload,
                                    List<SagaStepDefinition> steps) {
        SagaInstance existing = sagaInstanceRepository.findBySagaId(sagaId);
        if (existing != null) {
            if (existing.isTerminal()) {
                log.info("SAGA_REPLAY_NOOP | sagaId={} | status={}", sagaId, existing.getStatus());
                return existing;
            }
            throw new BusinessException("Saga already in progress: " + sagaId);
        }

        if (steps == null || steps.isEmpty()) {
            throw new BusinessException("Saga requires at least one step: " + sagaId);
        }

        SagaInstance saga = SagaInstance.start(sagaType, sagaId, payload);
        sagaInstanceRepository.save(saga);

        for (int i = 0; i < steps.size(); i++) {
            SagaStepDefinition step = steps.get(i);
            sagaStepRepository.save(SagaStep.newStep(saga, i, step.name(), payload));
        }

        try {
            for (int i = 0; i < steps.size(); i++) {
                SagaStepDefinition step = steps.get(i);
                saga.updateStep(step.name(), SagaInstance.STATUS_STEP_COMPLETED);
                sagaInstanceRepository.save(saga);

                SagaStep persisted = findStep(saga, i);
                try {
                    String compensationPayload = step.action().execute(step.name(), payload);
                    persisted.markCompleted(compensationPayload);
                } catch (RuntimeException ex) {
                    // The step's action threw after it may already have performed
                    // side effects. Persist the failure on THIS step row (previously
                    // left PENDING with no error) so saga_steps records exactly which
                    // step failed and why, then unwind to compensation. The failing
                    // step is intentionally NOT compensated — only prior completed steps are.
                    persisted.markFailed(ex.getMessage() != null
                            ? ex.getMessage()
                            : ex.getClass().getSimpleName());
                    sagaStepRepository.save(persisted);
                    throw ex;
                }
                sagaStepRepository.save(persisted);
                log.debug("SAGA_STEP_COMPLETED | sagaId={} | step={}", sagaId, step.name());
            }
            saga.markCompleted();
            sagaInstanceRepository.save(saga);
            log.info("SAGA_COMPLETED | sagaId={} | steps={}", sagaId, steps.size());
            return saga;
        } catch (Exception ex) {
            log.warn("SAGA_STEP_FAILED | sagaId={} | step={} | error={}",
                    sagaId, saga.getCurrentStep(), ex.getMessage());
            int failedIndex = indexOfStep(saga, steps);
            return compensate(saga, steps, failedIndex, ex);
        }
    }

    private SagaInstance compensate(SagaInstance saga, List<SagaStepDefinition> steps,
                                    int failedIndex, Exception cause) {
        saga.updateStep(saga.getCurrentStep(), SagaInstance.STATUS_COMPENSATING);
        sagaInstanceRepository.save(saga);

        boolean allCompensated = true;
        for (int i = failedIndex - 1; i >= 0; i--) {
            SagaStepDefinition step = steps.get(i);
            SagaStep persisted = findStep(saga, i);
            try {
                step.action().compensate(step.name(), saga.getPayload(), persisted.getCompensationPayload());
                persisted.markCompensated();
                sagaStepRepository.save(persisted);
                log.info("SAGA_STEP_COMPENSATED | sagaId={} | step={}", saga.getSagaId(), step.name());
            } catch (Exception compensationEx) {
                allCompensated = false;
                log.error("SAGA_COMPENSATION_FAILED | sagaId={} | step={} | error={}",
                        saga.getSagaId(), step.name(), compensationEx.getMessage());
            }
        }

        if (allCompensated) {
            saga.markCompensated();
            sagaInstanceRepository.save(saga);
            log.info("SAGA_COMPENSATED | sagaId={}", saga.getSagaId());
            return saga;
        }

        saga.markFailed();
        sagaInstanceRepository.save(saga);
        throw new BusinessException("Saga failed and compensation incomplete | sagaId="
                + saga.getSagaId() + " | cause=" + cause.getMessage());
    }

    private SagaStep findStep(SagaInstance saga, int stepOrder) {
        return sagaStepRepository.findBySagaInstanceIdAndStepOrder(saga.getId(), stepOrder)
                .orElseThrow(() -> new IllegalStateException("Saga step not found: "
                        + saga.getSagaId() + "#" + stepOrder));
    }

    private int indexOfStep(SagaInstance saga, List<SagaStepDefinition> steps) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).name().equals(saga.getCurrentStep())) {
                return i;
            }
        }
        return steps.size();
    }
}