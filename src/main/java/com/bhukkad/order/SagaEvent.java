package com.bhukkad.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "saga_instances", indexes = {
        @Index(name = "idx_saga_type_status", columnList = "saga_type, status"),
        @Index(name = "idx_saga_id", columnList = "saga_id")
})
@Getter
@Setter
public class SagaEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Type of saga, e.g., "ORDER_CREATION", "PAYMENT_SETTLEMENT"
     */
    @Column(nullable = false, length = 50)
    private String sagaType;

    /**
     * Unique identifier for the saga instance, often a business key like orderId
     */
    @Column(nullable = false, length = 100, unique = true)
    private String sagaId;

    /**
     * Current step being executed or last completed step
     */
    @Column(length = 50)
    private String currentStep;

    /**
     * Status of the saga: STARTED, STEP_COMPLETED, COMPLETED, COMPENSATING, COMPENSATED, FAILED
     */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * Serialized payload containing input data for the saga
     */
    @Column(columnDefinition = "JSON")
    private String payload;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    // Constructor for creating a new saga instance
    public static SagaEvent start(String sagaType, String sagaId, String payload) {
        SagaEvent event = new SagaEvent();
        event.setSagaType(sagaType);
        event.setSagaId(sagaId);
        event.setStatus("STARTED");
        event.setPayload(payload);
        return event;
    }

    public void updateStep(String stepName, String status) {
        this.setCurrentStep(stepName);
        this.setStatus(status);
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markCompleted() {
        this.setStatus("COMPLETED");
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markFailed() {
        this.setStatus("FAILED");
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markCompensating() {
        this.setStatus("COMPENSATING");
        this.setUpdatedAt(LocalDateTime.now());
    }

    public void markCompensated() {
        this.setStatus("COMPENSATED");
        this.setUpdatedAt(LocalDateTime.now());
    }
}