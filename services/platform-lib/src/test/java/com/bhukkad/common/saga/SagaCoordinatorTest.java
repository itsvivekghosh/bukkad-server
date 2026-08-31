package com.bhukkad.common.saga;

import com.bhukkad.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SagaCoordinatorTest {

    @Mock
    private SagaInstanceRepository instanceRepository;
    @Mock
    private SagaStepRepository stepRepository;

    private SagaCoordinator coordinator;

    private SagaCoordinator coordinator() {
        return new SagaCoordinator(instanceRepository, stepRepository);
    }

    private void stubLookups(SagaInstance saga) {
        when(instanceRepository.findBySagaId(saga.getSagaId())).thenReturn(null);
        when(instanceRepository.save(any(SagaInstance.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(stepRepository.save(any(SagaStep.class))).thenAnswer(inv -> inv.getArgument(0));
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            lenient().when(stepRepository.findBySagaInstanceIdAndStepOrder(any(), org.mockito.ArgumentMatchers.eq(idx)))
                    .thenAnswer(inv -> {
                        SagaStep step = SagaStep.newStep(saga, idx, "step-" + idx, "{}");
                        step.setId((long) idx + 1);
                        step.setCompensationPayload("{}");
                        return Optional.of(step);
                    });
        }
    }

    @Test
    void executeSaga_happyPath_completesAllStepsInOrder() {
        SagaInstance saga = SagaInstance.start("ORDER_CREATION", "saga-1", "{}");
        stubLookups(saga);
        List<String> executed = new java.util.ArrayList<>();
        List<SagaStepDefinition> steps = List.of(
                SagaStepDefinition.of("RESERVE", action(executed, "reserved", null)),
                SagaStepDefinition.of("PAY", action(executed, "paid", null)));

        SagaInstance result = coordinator().executeSaga("ORDER_CREATION", "saga-1", "{}", steps);

        assertThat(result.getStatus()).isEqualTo(SagaInstance.STATUS_COMPLETED);
        assertThat(executed).containsExactly("RESERVE", "PAY");
        verify(instanceRepository, org.mockito.Mockito.atLeast(2)).save(any(SagaInstance.class));
    }

    @Test
    void executeSaga_stepFailure_compensatesInReverseOrder() {
        SagaInstance saga = SagaInstance.start("ORDER_CREATION", "saga-2", "{}");
        stubLookups(saga);
        List<String> order = new java.util.ArrayList<>();
        SagaAction step0 = new SagaAction() {
            @Override public String execute(String n, String p) { order.add("EXEC0"); return "c0"; }
            @Override public void compensate(String n, String p, String c) { order.add("COMP0"); }
        };
        SagaAction step1 = new SagaAction() {
            @Override public String execute(String n, String p) { order.add("EXEC1"); throw new IllegalStateException("pay failed"); }
            @Override public void compensate(String n, String p, String c) { order.add("COMP1"); }
        };

        SagaInstance result = coordinator().executeSaga("ORDER_CREATION", "saga-2", "{}",
                List.of(SagaStepDefinition.of("STEP0", step0), SagaStepDefinition.of("STEP1", step1)));

        assertThat(result.getStatus()).isEqualTo(SagaInstance.STATUS_COMPENSATED);
        assertThat(order).containsExactly("EXEC0", "EXEC1", "COMP0");
    }

    @Test
    void executeSaga_compensationFailure_marksFailedAndThrows() {
        SagaInstance saga = SagaInstance.start("ORDER_CREATION", "saga-3", "{}");
        stubLookups(saga);
        SagaAction step0 = new SagaAction() {
            @Override public String execute(String n, String p) { return "c0"; }
            @Override public void compensate(String n, String p, String c) { throw new IllegalStateException("comp failed"); }
        };
        SagaAction step1 = new SagaAction() {
            @Override public String execute(String n, String p) { throw new IllegalStateException("fail"); }
            @Override public void compensate(String n, String p, String c) { }
        };

        assertThatThrownBy(() -> coordinator().executeSaga("ORDER_CREATION", "saga-3", "{}",
                List.of(SagaStepDefinition.of("STEP0", step0), SagaStepDefinition.of("STEP1", step1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("compensation incomplete");
    }

    @Test
    void executeSaga_replayOfTerminalSaga_isNoop() {
        SagaInstance terminal = SagaInstance.start("ORDER_CREATION", "saga-4", "{}");
        terminal.markCompleted();
        when(instanceRepository.findBySagaId("saga-4")).thenReturn(terminal);
        AtomicInteger executions = new AtomicInteger();
        SagaAction never = new SagaAction() {
            @Override public String execute(String n, String p) { executions.incrementAndGet(); return "x"; }
            @Override public void compensate(String n, String p, String c) { }
        };

        SagaInstance result = coordinator().executeSaga("ORDER_CREATION", "saga-4", "{}",
                List.of(SagaStepDefinition.of("STEP", never)));

        assertThat(result).isSameAs(terminal);
        assertThat(executions.get()).isZero();
    }

    @Test
    void executeSaga_inProgressSaga_throws() {
        SagaInstance inProgress = SagaInstance.start("ORDER_CREATION", "saga-5", "{}");
        when(instanceRepository.findBySagaId("saga-5")).thenReturn(inProgress);

        assertThatThrownBy(() -> coordinator().executeSaga("ORDER_CREATION", "saga-5", "{}",
                List.of(SagaStepDefinition.of("STEP", noopAction()))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already in progress");
    }

    @Test
    void executeSaga_noSteps_throws() {
        when(instanceRepository.findBySagaId("saga-6")).thenReturn(null);

        assertThatThrownBy(() -> coordinator().executeSaga("ORDER_CREATION", "saga-6", "{}", List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one step");
    }

    private static SagaAction action(List<String> order, String exec, String compensation) {
        return new SagaAction() {
            @Override public String execute(String n, String p) { order.add(n); return compensation; }
            @Override public void compensate(String n, String p, String c) { }
        };
    }

    private static SagaAction noopAction() {
        return new SagaAction() {
            @Override public String execute(String n, String p) { return null; }
            @Override public void compensate(String n, String p, String c) { }
        };
    }
}
