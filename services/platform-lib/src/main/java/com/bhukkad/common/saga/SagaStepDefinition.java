package com.bhukkad.common.saga;

/**
 * A single saga step definition: the step name plus the forward and
 * compensation actions. Compensation payloads produced by
 * {@link SagaAction#execute} are stored per step so {@link SagaAction#compensate}
 * can undo the step with exactly the state it needs.
 */
public record SagaStepDefinition(
        String name,
        SagaAction action
) {
    public static SagaStepDefinition of(String name, SagaAction action) {
        return new SagaStepDefinition(name, action);
    }
}