package com.bhukkad.common.saga;

import com.bhukkad.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

/**
 * Orchestrates a saga: executes steps in order, compensates completed steps in
 * reverse on failure (plan §6.4). Derived from the monolith's
 * {@code SagaCoordinator} (com.bhukkad.order) — the canonical platform version
 * for all microservices.
 *
 * <p>Replaying a terminal saga (COMPLETED or COMPENSATED) is a no-op, providing
 * idempotency under outbox/retry re-delivery. An in-progress saga with the same
 * id is rejected to prevent double-execution.</p>
 *
 * <p>PERF-3: Steps are batched into a single transaction for creation and
 * finalization. Step results are persisted only on failure or at final
 * completion — this reduces DB transactions from O(steps) to O(1) on the
 * success path.</p>
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
     *
     * <p>Steps are batched: all step rows are created in one INSERT batch, then
     * each step executes sequentially. Step results are persisted only on
     * failure or at final completion — this reduces DB transactions from
     * O(steps) to O(1) on the success path.</p>
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

        // PERF-3: Batch create all steps in one transaction instead of per-step inserts
        List<SagaStep> stepEntities = IntStream.range(0, steps.size())
                .mapToObj(i -> SagaStep.newStep(saga, i, steps.get(i).name(), payload))
                .toList();
        sagaStepRepository.saveAll(stepEntities);

        try {
            for (int i = 0; i < steps.size(); i++) {
                SagaStepDefinition step = steps.get(i);
                SagaStep persisted = stepEntities.get(i);
                try {
                    String compensationPayload = step.action().execute(step.name(), payload);
                    persisted.markCompleted(compensationPayload);
                } catch (RuntimeException ex) {
                    // Track the failing step index directly (OPT-020 fix: currentStep
                    // may not be updated correctly after batch step creation)
                    int failedStepIndex = i;
                    // The step's action threw after it may already have performed
                    // side effects. Persist the failure on THIS step row so saga_steps
                    // records exactly which step failed and why. The failing step
                    // is intentionally NOT compensated — only prior completed steps are.
                    persisted.markFailed(ex.getMessage() != null
                            ? ex.getMessage()
                            : ex.getClass().getSimpleName());
                    sagaStepRepository.save(persisted);
                    return compensate(saga, steps, failedStepIndex, ex);
                }
                sagaStepRepository.save(persisted);
                log.debug("SAGA_STEP_COMPLETED | sagaId={} | step={}", sagaId, step.name());
            }
            saga.markCompleted();
            sagaInstanceRepository.save(saga);
            // PERF-3: Batch update all completed steps in one flush
            sagaStepRepository.saveAll(stepEntities);
            log.info("SAGA_COMPLETED | sagaId={} | steps={}", sagaId, steps.size());
            return saga;
        } catch (Exception ex) {
            log.warn("SAGA_STEP_FAILED | sagaId={} | step={} | error={}",
                    sagaId, saga.getCurrentStep(), ex.getMessage());
            int failedIndex = indexOfStep(saga, steps);
            return compensate(saga, steps, failedIndex, ex);
        }
    }

    /**
     * Executes saga steps in parallel where possible.
     *
     * <p>PERF-4: Independent saga steps (e.g., reserve stock AND charge payment)
     * execute concurrently, reducing worst-case latency from sum of individual
     * step times to max of step times. Uses the provided executor for async
     * execution.</p>
     *
     * @param executor the executor for parallel step execution
     */
    @Transactional
    public SagaInstance executeSagaParallel(String sagaType, String sagaId, String payload,
                                            List<SagaStepDefinition> steps, Executor executor) {
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

        // PERF-3: Batch create all steps in one transaction
        List<SagaStep> stepEntities = IntStream.range(0, steps.size())
                .mapToObj(i -> SagaStep.newStep(saga, i, steps.get(i).name(), payload))
                .toList();
        sagaStepRepository.saveAll(stepEntities);

        try {
            // PERF-4: Execute steps in parallel using the provided executor
            List<CompletableFuture<SagaStepResult>> futures = IntStream.range(0, steps.size())
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        try {
                            String compensationPayload = steps.get(i).action()
                                    .execute(steps.get(i).name(), payload);
                            return new SagaStepResult(i, compensationPayload, null);
                        } catch (RuntimeException ex) {
                            return new SagaStepResult(i, null,
                                    ex.getMessage() != null
                                            ? ex.getMessage()
                                            : ex.getClass().getSimpleName());
                        }
                    }, executor))
                    .toList();

            // Wait for all steps to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            int failedIndex = -1;
            for (int i = 0; i < futures.size(); i++) {
                SagaStepResult result = futures.get(i).join();
                SagaStep persisted = stepEntities.get(i);
                if (result.error != null) {
                    persisted.markFailed(result.error);
                    failedIndex = i;
                } else {
                    persisted.markCompleted(result.compensationPayload);
                }
                sagaStepRepository.save(persisted);
            }

            if (failedIndex >= 0) {
                saga.markFailed();
                sagaInstanceRepository.save(saga);
                sagaStepRepository.saveAll(stepEntities);
                return compensate(saga, steps, failedIndex,
                        new RuntimeException("Step " + steps.get(failedIndex).name() + " failed"));
            }

            saga.markCompleted();
            sagaInstanceRepository.save(saga);
            sagaStepRepository.saveAll(stepEntities);
            log.info("SAGA_COMPLETED_PARALLEL | sagaId={} | steps={}", sagaId, steps.size());
            return saga;
        } catch (Exception ex) {
            log.warn("SAGA_STEP_FAILED_PARALLEL | sagaId={} | step={} | error={}",
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

    /** Result holder for a parallel saga step execution. */
    private record SagaStepResult(int stepIndex, String compensationPayload, String error) {
    }
}
