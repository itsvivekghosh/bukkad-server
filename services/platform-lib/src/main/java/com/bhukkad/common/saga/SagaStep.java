package com.bhukkad.common.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One step of a saga ({@code saga_steps}). Compensation payloads are plain
 * TEXT (see {@link SagaInstance} note on jsonb).
 */
@Entity
@Table(name = "saga_steps", indexes = {
        @Index(name = "idx_saga_instance_status", columnList = "saga_instance_id, status"),
        @Index(name = "idx_saga_steps_pending", columnList = "status, step_order")
})
@Getter
@Setter
public class SagaStep {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_COMPENSATED = "COMPENSATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "saga_instance_id", nullable = false)
    private SagaInstance sagaInstance;

    @Column(nullable = false)
    private int stepOrder;

    @Column(nullable = false, length = 50)
    private String stepName;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(columnDefinition = "TEXT")
    private String compensationPayload;

    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public static SagaStep newStep(SagaInstance sagaInstance, int stepOrder, String stepName, String payload) {
        SagaStep step = new SagaStep();
        step.setSagaInstance(sagaInstance);
        step.setStepOrder(stepOrder);
        step.setStepName(stepName);
        step.setStatus(STATUS_PENDING);
        step.setPayload(payload);
        return step;
    }

    public void markCompleted(String compensationPayload) {
        this.setStatus(STATUS_COMPLETED);
        this.setCompensationPayload(compensationPayload);
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markFailed(String errorMessage) {
        this.setStatus(STATUS_FAILED);
        this.setErrorMessage(errorMessage);
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markCompensated() {
        this.setStatus(STATUS_COMPENSATED);
        this.setUpdatedAt(LocalDateTime.now());
    }
}
