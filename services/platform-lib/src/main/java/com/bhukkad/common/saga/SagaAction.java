package com.bhukkad.common.saga;

/**
 * Functional contract for one saga step. The forward {@link #execute} runs the
 * step and returns a JSON compensation payload capturing whatever is needed to
 * undo it (pre-step state, ids, amounts). {@link #compensate} runs the inverse
 * using that stored payload.
 */
public interface SagaAction {

    /**
     * Executes the step.
     *
     * @param stepName the step name
     * @param payload  the step input (JSON string; may embed orderId etc.)
     * @return a JSON compensation payload, or {@code null} when the step cannot
     *         be compensated (e.g. a pure read)
     */
    String execute(String stepName, String payload);

    /**
     * Undoes a previously completed step.
     *
     * @param stepName            the step name
     * @param payload             the original step input
     * @param compensationPayload the payload returned by {@link #execute}
     */
    void compensate(String stepName, String payload, String compensationPayload);
}