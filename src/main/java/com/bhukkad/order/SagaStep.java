package com.bhukkad.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "saga_steps", indexes = {
        @Index(name = "idx_saga_instance_status", columnList = "saga_instance_id, status"),
        @Index(name = "idx_saga_steps_pending", columnList = "status, step_order")
})
@Getter
@Setter
public class SagaStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "saga_instance_id", nullable = false)
    private SagaEvent sagaInstance;

    /**
     * Order of this step within the saga (0-based)
     */
    @Column(nullable = false)
    private int stepOrder;

    /**
     * Name of the step, e.g., "RESERVE_INVENTORY", "PROCESS_PAYMENT"
     */
    @Column(nullable = false, length = 50)
    private String stepName;

    /**
     * Status of the step: PENDING, COMPLETED, FAILED, COMPENSATED
     */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * Serialized input payload for this step
     */
    @Column(columnDefinition = "JSON")
    private String payload;

    /**
     * Serialized payload needed to compensate this step (e.g., pre-step state)
     */
    @Column(columnDefinition = "JSON")
    private String compensationPayload;

    /**
     * Error message if the step failed
     */
    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    // Factory method to create a new step
    public static SagaStep newStep(SagaEvent sagaInstance, int stepOrder, String stepName, String payload) {
        SagaStep step = new SagaStep();
        step.setSagaInstance(sagaInstance);
        step.setStepOrder(stepOrder);
        step.setStepName(stepName);
        step.setStatus("PENDING");
        step.setPayload(payload);
        return step;
    }

    public void markCompleted(String compensationPayload) {
        this.setStatus("COMPLETED");
        this.setCompensationPayload(compensationPayload);
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markFailed(String errorMessage) {
        this.setStatus("FAILED");
        this.setErrorMessage(errorMessage);
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markCompensated() {
        this.setStatus("COMPENSATED");
        this.setUpdatedAt(LocalDateTime.now());
    }
}