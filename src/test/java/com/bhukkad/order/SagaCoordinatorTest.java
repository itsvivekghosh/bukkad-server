package com.bhukkad.order;

import com.bhukkad.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SagaCoordinator}: happy path, step failure with
 * compensation in reverse order, idempotent replay, and in-progress guard.
 * The persistence layer is mocked; a fake repository tracks persisted state.
 */
@ExtendWith(MockitoExtension.class)
class SagaCoordinatorTest {

    private static final String SAGA_ID = "ORDER-42";

    @Mock
    private SagaEventRepository sagaEventRepository;

    @Mock
    private SagaStepRepository sagaStepRepository;

    private SagaCoordinator coordinator;
    private final List<String> executed = new ArrayList<>();
    private final List<String> compensated = new ArrayList<>();

    @BeforeEach
    void setUp() {
        coordinator = new SagaCoordinator(sagaEventRepository, sagaStepRepository);
        executed.clear();
        compensated.clear();
        fakeSteps.clear();
        org.mockito.Mockito.lenient().when(sagaEventRepository.save(any(SagaEvent.class))).thenAnswer(inv -> {
            SagaEvent saga = inv.getArgument(0);
            if (saga.getId() == null) {
                saga.setId(1L); // simulate DB identity assignment
            }
            return saga;
        });
        org.mockito.Mockito.lenient().when(sagaStepRepository.save(any(SagaStep.class))).thenAnswer(inv -> {
            SagaStep step = inv.getArgument(0);
            fakeSteps.add(step);
            return step;
        });
        org.mockito.Mockito.lenient()
                .when(sagaStepRepository.findBySagaInstanceIdAndStepOrder(
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> findFakeStep(inv.getArgument(0), inv.getArgument(1)));
    }

    // Fake step store so markCompleted/markCompensated state is observable.
    private final List<SagaStep> fakeSteps = new ArrayList<>();

    private Optional<SagaStep> findFakeStep(Long sagaInstanceId, int stepOrder) {
        return fakeSteps.stream()
                .filter(s -> s.getSagaInstance().getId().equals(sagaInstanceId))
                .filter(s -> s.getStepOrder() == stepOrder)
                .findFirst();
    }

    private SagaAction action(String name) {
        return new SagaAction() {
            @Override
            public String execute(String stepName, String payload) {
                executed.add(name);
                return "comp-payload-for-" + name;
            }

            @Override
            public void compensate(String stepName, String payload, String compensationPayload) {
                compensated.add(name);
            }
        };
    }

    private List<SagaStepDefinition> steps(SagaAction... actions) {
        List<SagaStepDefinition> defs = new ArrayList<>();
        for (int i = 0; i < actions.length; i++) {
            defs.add(SagaStepDefinition.of("STEP_" + (i + 1), actions[i]));
        }
        return defs;
    }

    @Test
    void executeSaga_happyPath_completesAllSteps() {
        when(sagaEventRepository.findBySagaId(SAGA_ID)).thenReturn(null);

        SagaEvent result = coordinator.executeSaga(
                "ORDER_CREATION", SAGA_ID, "{\"orderId\":42}",
                steps(action("reserve"), action("pay")));

        assertEquals(SagaCoordinator.STATUS_COMPLETED, result.getStatus());
        assertEquals(List.of("reserve", "pay"), executed);
        assertTrue(compensated.isEmpty());
    }

    @Test
    void executeSaga_stepFailure_compensatesCompletedStepsInReverse() {
        when(sagaEventRepository.findBySagaId(SAGA_ID)).thenReturn(null);
        SagaAction failingPay = new SagaAction() {
            @Override
            public String execute(String stepName, String payload) {
                executed.add("pay");
                throw new IllegalStateException("payment gateway down");
            }

            @Override
            public void compensate(String stepName, String payload, String compensationPayload) {
                // no-op: the failing step never completes, so no compensation
                // is ever invoked for it in this test scenario.
            }
        };

        SagaEvent result = coordinator.executeSaga(
                "ORDER_CREATION", SAGA_ID, "{\"orderId\":42}",
                steps(action("reserve"), failingPay, action("settle")));

        assertEquals(SagaCoordinator.STATUS_COMPENSATED, result.getStatus());
        // Compensation runs only for completed steps, in REVERSE order.
        assertEquals(List.of("reserve"), compensated);
        assertEquals(SagaCoordinator.STATUS_COMPENSATED, result.getStatus());
    }

    @Test
    void executeSaga_compensationOrder_isReverseOfExecution() {
        when(sagaEventRepository.findBySagaId(SAGA_ID)).thenReturn(null);
        SagaAction failing = new SagaAction() {
            @Override
            public String execute(String stepName, String payload) {
                throw new IllegalStateException("boom at step 3");
            }

            @Override
            public void compensate(String stepName, String payload, String compensationPayload) {
                // no-op: this test only asserts compensation ORDER; the failing
                // step is never compensated, and the successful steps record
                // their invocation via the compensate() implementations below.
            }
        };

        coordinator.executeSaga("ORDER_CREATION", SAGA_ID, "{}",
                steps(action("a"), action("b"), action("c"), failing));

        assertEquals(List.of("a", "b", "c"), executed);
        assertEquals(List.of("c", "b", "a"), compensated, "compensation must run in reverse step order");
    }

    @Test
    void executeSaga_compensationFailure_marksFailedAndThrows() {
        when(sagaEventRepository.findBySagaId(SAGA_ID)).thenReturn(null);
        SagaAction failingStep = new SagaAction() {
            @Override
            public String execute(String stepName, String payload) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void compensate(String stepName, String payload, String compensationPayload) {
                // no-op: this step is not expected to fail in this scenario.
            }
        };
        SagaAction brokenCompensation = new SagaAction() {
            @Override
            public String execute(String stepName, String payload) {
                executed.add("reserve");
                return "comp";
            }

            @Override
            public void compensate(String stepName, String payload, String compensationPayload) {
                compensated.add("reserve");
                throw new IllegalStateException("compensation failed");
            }
        };

        BusinessException ex = assertThrows(BusinessException.class,
                () -> coordinator.executeSaga("ORDER_CREATION", SAGA_ID, "{}",
                        steps(brokenCompensation, failingStep)));

        assertTrue(ex.getMessage().contains("compensation incomplete"));
        // The saga instance is saved as FAILED when compensation cannot complete.
        org.mockito.ArgumentCaptor<SagaEvent> captor = org.mockito.ArgumentCaptor.forClass(SagaEvent.class);
        org.mockito.Mockito.verify(sagaEventRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        SagaEvent lastSave = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(SagaCoordinator.STATUS_FAILED, lastSave.getStatus());
    }

    @Test
    void executeSaga_completedSagaReplay_isNoop() {
        SagaEvent completed = SagaEvent.start("ORDER_CREATION", SAGA_ID, "{}");
        completed.markCompleted();
        when(sagaEventRepository.findBySagaId(SAGA_ID)).thenReturn(completed);

        SagaEvent result = coordinator.executeSaga("ORDER_CREATION", SAGA_ID, "{}", steps(action("a")));

        assertEquals(SagaCoordinator.STATUS_COMPLETED, result.getStatus());
        assertTrue(executed.isEmpty(), "replay of a completed saga must not re-run steps");
    }

    @Test
    void executeSaga_compensatedSagaReplay_isNoop() {
        SagaEvent compensated = SagaEvent.start("ORDER_CREATION", SAGA_ID, "{}");
        compensated.markCompensated();
        when(sagaEventRepository.findBySagaId(SAGA_ID)).thenReturn(compensated);

        SagaEvent result = coordinator.executeSaga("ORDER_CREATION", SAGA_ID, "{}", steps(action("a")));

        assertEquals(SagaCoordinator.STATUS_COMPENSATED, result.getStatus());
        assertTrue(executed.isEmpty());
    }

    @Test
    void executeSaga_sagaInProgress_throws() {
        SagaEvent inProgress = SagaEvent.start("ORDER_CREATION", SAGA_ID, "{}");
        when(sagaEventRepository.findBySagaId(SAGA_ID)).thenReturn(inProgress);

        assertThrows(BusinessException.class,
                () -> coordinator.executeSaga("ORDER_CREATION", SAGA_ID, "{}", steps(action("a"))));
        assertTrue(executed.isEmpty());
    }

    @Test
    void executeSaga_stepFailure_marksFailedStepFailedWithErrorMessage() {
        when(sagaEventRepository.findBySagaId(SAGA_ID)).thenReturn(null);
        SagaAction failingStep = new SagaAction() {
            @Override
            public String execute(String stepName, String payload) {
                throw new IllegalStateException("step execution exploded");
            }

            @Override
            public void compensate(String stepName, String payload, String compensationPayload) {
            }
        };

        SagaEvent result = coordinator.executeSaga(
                "ORDER_CREATION", SAGA_ID, "{}",
                steps(action("reserve"), failingStep));

        // The saga instance should be COMPENSATED (failed step is not compensated,
        // but prior steps are).
        assertEquals(SagaCoordinator.STATUS_COMPENSATED, result.getStatus());

        // The FAILED step (index 1) should be marked FAILED with the error message,
        // not left PENDING as a step that "completed".
        ArgumentCaptor<SagaStep> stepCaptor = ArgumentCaptor.forClass(SagaStep.class);
        verify(sagaStepRepository, atLeastOnce()).save(stepCaptor.capture());
        List<SagaStep> savedSteps = stepCaptor.getAllValues();
        SagaStep failedStep = savedSteps.stream()
                .filter(s -> s.getStepOrder() == 1)
                .findFirst()
                .orElseThrow();
        assertEquals("FAILED", failedStep.getStatus());
        assertTrue(failedStep.getErrorMessage().contains("step execution exploded"));

        // The saga instance should NOT have been saved as STEP_COMPLETED for the
        // failing step.
        ArgumentCaptor<SagaEvent> eventCaptor = ArgumentCaptor.forClass(SagaEvent.class);
        verify(sagaEventRepository, atLeastOnce()).save(eventCaptor.capture());
        SagaEvent lastEvent = eventCaptor.getAllValues().get(eventCaptor.getAllValues().size() - 1);
        assertEquals(SagaCoordinator.STATUS_COMPENSATED, lastEvent.getStatus());
    }
}
