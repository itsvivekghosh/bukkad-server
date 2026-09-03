package com.bhukkad.order;

import com.bhukkad.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Executes a saga (sequence of steps) with per-step compensation. Used for the
 * distributed create-order → payment → settlement flow without 2PC: each step
 * commits independently; when any step fails, the previously completed steps
 * are compensated in reverse order.
 *
 * <p>State is persisted in {@code saga_instances} / {@code saga_steps} (V51).
 * Replaying an already-terminal saga is a no-op, which makes the caller
 * idempotent under outbox/retry re-delivery.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaCoordinator {

    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_STEP_COMPLETED = "STEP_COMPLETED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_COMPENSATING = "COMPENSATING";
    public static final String STATUS_COMPENSATED = "COMPENSATED";
    public static final String STATUS_FAILED = "FAILED";

    private final SagaEventRepository sagaEventRepository;
    private final SagaStepRepository sagaStepRepository;

    /**
     * Runs the saga. Returns the terminal {@link SagaEvent} (COMPLETED or
     * COMPENSATED). Throws {@link BusinessException} when the saga fails and
     * compensation could not be completed, or when a saga with the same id is
     * already in progress.
     */
    @Transactional
    public SagaEvent executeSaga(String sagaType, String sagaId, String payload,
                                 List<SagaStepDefinition> steps) {
        SagaEvent existing = sagaEventRepository.findBySagaId(sagaId);
        if (existing != null) {
            if (STATUS_COMPLETED.equals(existing.getStatus())
                    || STATUS_COMPENSATED.equals(existing.getStatus())) {
                log.info("SAGA_REPLAY_NOOP | sagaId={} | status={}", sagaId, existing.getStatus());
                return existing;
            }
            throw new BusinessException("Saga already in progress: " + sagaId);
        }

        if (steps == null || steps.isEmpty()) {
            throw new BusinessException("Saga requires at least one step: " + sagaId);
        }

        SagaEvent saga = SagaEvent.start(sagaType, sagaId, payload);
        sagaEventRepository.save(saga);

        for (int i = 0; i < steps.size(); i++) {
            SagaStepDefinition step = steps.get(i);
            sagaStepRepository.save(SagaStep.newStep(saga, i, step.name(), payload));
        }

        try {
            for (int i = 0; i < steps.size(); i++) {
                SagaStepDefinition step = steps.get(i);
                // Fetch the persisted step so we can mark it failed on execution error
                SagaStep persisted = findStep(saga, i);
                saga.setCurrentStep(step.name());
                sagaEventRepository.save(saga);

                try {
                    String compensationPayload = step.action().execute(step.name(), payload);
                    persisted.markCompleted(compensationPayload);
                    sagaStepRepository.save(persisted);
                    saga.updateStep(step.name(), STATUS_STEP_COMPLETED);
                    sagaEventRepository.save(saga);
                    log.debug("SAGA_STEP_COMPLETED | sagaId={} | step={}", sagaId, step.name());
                } catch (RuntimeException ex) {
                    // Mark the failed step with the error before unwinding compensation.
                    // The step is NOT compensated (matches intent: it never completed).
                    persisted.markFailed(ex.getMessage());
                    sagaStepRepository.save(persisted);
                    log.warn("SAGA_STEP_FAILED | sagaId={} | step={} | error={}",
                            sagaId, step.name(), ex.getMessage());
                    saga.updateStep(step.name(), STATUS_FAILED);
                    sagaEventRepository.save(saga);
                    return compensate(saga, steps, i, payload, ex);
                }
            }
            saga.markCompleted();
            sagaEventRepository.save(saga);
            log.info("SAGA_COMPLETED | sagaId={} | steps={}", sagaId, steps.size());
            return saga;
        } catch (Exception ex) {
            log.warn("SAGA_STEP_FAILED | sagaId={} | step={} | error={}",
                    sagaId, saga.getCurrentStep(), ex.getMessage());
            int failedIndex = indexOfStep(saga, steps);
            return compensate(saga, steps, failedIndex, payload, ex);
        }
    }

    /**
     * Compensates completed steps 0..failedIndex-1 in reverse order. Marks the
     * saga COMPENSATED when every compensation succeeds, FAILED otherwise.
     */
    private SagaEvent compensate(SagaEvent saga, List<SagaStepDefinition> steps,
                                 int failedIndex, String payload, Exception cause) {
        saga.updateStep(saga.getCurrentStep(), STATUS_COMPENSATING);
        sagaEventRepository.save(saga);

        boolean allCompensated = true;
        for (int i = failedIndex - 1; i >= 0; i--) {
            SagaStepDefinition step = steps.get(i);
            SagaStep persisted = findStep(saga, i);
            try {
                step.action().compensate(step.name(), payload, persisted.getCompensationPayload());
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
            sagaEventRepository.save(saga);
            log.info("SAGA_COMPENSATED | sagaId={}", saga.getSagaId());
            return saga;
        }

        saga.markFailed();
        sagaEventRepository.save(saga);
        throw new BusinessException("Saga failed and compensation incomplete | sagaId="
                + saga.getSagaId() + " | cause=" + cause.getMessage());
    }

    private SagaStep findStep(SagaEvent saga, int stepOrder) {
        return sagaStepRepository.findBySagaInstanceIdAndStepOrder(saga.getId(), stepOrder)
                .orElseThrow(() -> new IllegalStateException("Saga step not found: "
                        + saga.getSagaId() + "#" + stepOrder));
    }

    private int indexOfStep(SagaEvent saga, List<SagaStepDefinition> steps) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).name().equals(saga.getCurrentStep())) {
                return i;
            }
        }
        return steps.size();
    }
}
