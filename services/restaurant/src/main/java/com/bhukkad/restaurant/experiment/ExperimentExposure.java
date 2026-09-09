package com.bhukkad.restaurant.experiment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "experiment_exposures",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_experiment_user", columnNames = {"experiment_key", "user_id"}),
        indexes = {
                @Index(name = "idx_experiment_variant", columnList = "experiment_key, variant"),
                @Index(name = "idx_experiment_exposed_at", columnList = "exposed_at")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class ExperimentExposure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_key", nullable = false, length = 80)
    private String experimentKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 80)
    private String variant;

    @Column(nullable = false)
    private int bucket;

    @CreatedDate
    @Column(name = "exposed_at", nullable = false, updatable = false)
    private LocalDateTime exposedAt;
}
