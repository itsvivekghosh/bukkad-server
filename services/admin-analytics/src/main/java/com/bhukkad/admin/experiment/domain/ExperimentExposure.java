package com.bhukkad.admin.experiment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * One exposure log entry: the moment a user was first assigned to a variant of an
 * experiment. The unique constraint on (experiment_key, user_id) means a user's
 * first touch wins and later requests never rewrite it, so the table doubles as
 * the source of truth for per-variant cohort sizes.
 *
 * <p>Ported from the monolith's {@code com.bhukkad.experiment.ExperimentExposure}
 * with identical columns so the gateway-exposed cohort census stays byte-compatible.</p>
 */
@Entity
@Table(name = "experiment_exposures",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_experiment_user", columnNames = {"experiment_key", "user_id"}),
        indexes = {
                @Index(name = "idx_experiment_variant", columnList = "experiment_key, variant"),
                @Index(name = "idx_experiment_exposed_at", columnList = "exposed_at")
        })
@Getter @Setter
@EntityListeners(AuditingEntityListener.class)
public class ExperimentExposure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Experiment key from {@code ExperimentProperties}, e.g. {@code checkout-cta-copy}. */
    @Column(name = "experiment_key", nullable = false, length = 80)
    private String experimentKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Assigned arm, e.g. {@code control} / {@code treatment}. */
    @Column(nullable = false, length = 80)
    private String variant;

    /** Bucket value that produced the assignment (0-9999) — makes assignments auditable. */
    @Column(nullable = false)
    private int bucket;

    @CreatedDate
    @Column(name = "exposed_at", nullable = false, updatable = false)
    private LocalDateTime exposedAt;
}
