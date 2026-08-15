package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Audit record for automated or manual settlement batch runs. */
@Entity
@Table(name = "settlement_runs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SettlementRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String runType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status = RunStatus.RUNNING;

    @Column(nullable = false)
    private Integer restaurantsSettled = 0;

    @Column(nullable = false)
    private Integer agentsSettled = 0;

    @Column(nullable = false)
    private Double totalAmount = 0.0;

    @Column(nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    private LocalDateTime completedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    public enum RunStatus {
        RUNNING, COMPLETED, FAILED
    }
}
