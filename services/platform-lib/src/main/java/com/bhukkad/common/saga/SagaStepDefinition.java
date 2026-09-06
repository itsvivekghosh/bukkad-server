package com.bhukkad.common.saga;

/**
 * A single saga step definition: the step name plus the forward and
 * compensation actions. Compensation payloads produced by
 * {@link SagaAction#execute} are stored per step so {@link SagaAction#compensate}
 * can undo the step with exactly the state it needs.
 *
 * <p>Steps come in two flavours (audit batch A):</p>
 * <ul>
 *   <li>{@link #of(String, SagaAction)} — the original contract: the action
 *       decides success/failure by returning / throwing (typically used by
 *       simulated placeholder steps).</li>
 *   <li>{@link #executionResult(String, java.util.function.Supplier,
 *       java.util.function.Consumer)} — a REAL executor step: a
 *       {@code Supplier<StepResult>} drives the saga outcome.
 *       {@link StepResult#SUCCESS} continues the chain;
 *       {@link StepResult#FAILED} marks the saga step FAILED and unwinds the
 *       compensation chain (handled by {@link SagaCoordinator}).</li>
 * </ul>
 */
public record SagaStepDefinition(
        String name,
        SagaAction action
) {

    /** Outcome of a real step execution under the {@link #executionResult} protocol. */
    public enum StepResult {
        SUCCESS,
        FAILED
    }

    public static SagaStepDefinition of(String name, SagaAction action) {
        return new SagaStepDefinition(name, action);
    }

    /**
     * Default behaviour preserving the simulated contract: a step whose
     * {@code execute()} always yields {@link StepResult#SUCCESS} and whose
     * compensation is a no-op. Callers that only need a structural placeholder
     * can migrate to the result protocol without changing observable behaviour.
     */
    public static SagaStepDefinition simulated(String name) {
        return executionResult(name, () -> StepResult.SUCCESS, payload -> { });
    }

    /**
     * A step backed by a real executor.
     *
     * @param name         step name (also recorded on the saga ledger)
     * @param executor     performs the forward action and reports its outcome;
     *                     {@link StepResult#FAILED} (and any exception the
     *                     supplier lets escape) fails the step
     * @param compensation undoes a previously completed execution of this step;
     *                     receives the saga payload. Runs only for steps before
     *                     a failed step, in reverse order, like any
     *                     {@link SagaAction#compensate}.
     */
    public static SagaStepDefinition executionResult(String name,
                                                     java.util.function.Supplier<StepResult> executor,
                                                     java.util.function.Consumer<String> compensation) {
        return new SagaStepDefinition(name, new SagaAction() {
            @Override
            public String execute(String stepName, String payload) {
                StepResult result = executor.get();
                if (result != StepResult.SUCCESS) {
                    throw new SagaStepExecutionException(
                            "Saga step returned FAILED: " + stepName);
                }
                // The saga payload is the compensation input for this protocol.
                return payload;
            }

            @Override
            public void compensate(String stepName, String payload, String compensationPayload) {
                compensation.accept(compensationPayload != null ? compensationPayload : payload);
            }
        });
    }
}
