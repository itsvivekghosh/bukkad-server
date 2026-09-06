package com.bhukkad.common.saga;

/**
 * Raised when a step built with {@link SagaStepDefinition#executionResult}
 * reports {@link SagaStepDefinition.StepResult#FAILED}. The {@link
 * SagaCoordinator} catches it (like any {@link RuntimeException}) to mark the
 * saga step FAILED and unwind the compensation chain, so the message lands in
 * {@code saga_steps.error_message}.
 */
public class SagaStepExecutionException extends RuntimeException {

    public SagaStepExecutionException(String message) {
        super(message);
    }

    public SagaStepExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
