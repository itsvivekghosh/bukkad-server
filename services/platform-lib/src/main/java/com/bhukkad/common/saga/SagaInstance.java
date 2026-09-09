package com.bhukkad.common.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Saga instance ({@code saga_instances}) — platform state for a compensating
 * transaction (plan §6.4). Payload is plain TEXT: the JPA contract maps it as a
 * String, which PostgreSQL rejects for jsonb columns (see the common PG
 * baseline for the full rationale).
 */
@Entity
@Table(name = "saga_instances", indexes = {
        @Index(name = "idx_saga_type_status", columnList = "saga_type, status"),
        @Index(name = "uq_saga_instances_saga_id", columnList = "saga_id", unique = true)
})
@Getter
@Setter
public class SagaInstance {

    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_STEP_COMPLETED = "STEP_COMPLETED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_COMPENSATING = "COMPENSATING";
    public static final String STATUS_COMPENSATED = "COMPENSATED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String sagaType;

    @Column(nullable = false, length = 100)
    private String sagaId;

    @Column(length = 50)
    private String currentStep;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public static SagaInstance start(String sagaType, String sagaId, String payload) {
        SagaInstance instance = new SagaInstance();
        instance.setSagaType(sagaType);
        instance.setSagaId(sagaId);
        instance.setStatus(STATUS_STARTED);
        instance.setPayload(payload);
        return instance;
    }

    public void updateStep(String stepName, String status) {
        this.setCurrentStep(stepName);
        this.setStatus(status);
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markCompleted() {
        this.setStatus(STATUS_COMPLETED);
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markFailed() {
        this.setStatus(STATUS_FAILED);
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markCompensating() {
        this.setStatus(STATUS_COMPENSATING);
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markCompensated() {
        this.setStatus(STATUS_COMPENSATED);
        this.setUpdatedAt(LocalDateTime.now());
    }

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status) || STATUS_COMPENSATED.equals(status);
    }
}
