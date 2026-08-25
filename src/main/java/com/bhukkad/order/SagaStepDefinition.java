package com.bhukkad.order;

/**
 * A single saga step definition: the step name plus the forward and
 * compensation actions. Compensation payloads produced by
 * {@link #execute} are stored per step so {@link #compensate} can undo the
 * step with exactly the state it needs (e.g. the reserved stock quantity or
 * the payment id).
 */
public record SagaStepDefinition(
        String name,
        SagaAction action
) {
    public static SagaStepDefinition of(String name, SagaAction action) {
        return new SagaStepDefinition(name, action);
    }
}
