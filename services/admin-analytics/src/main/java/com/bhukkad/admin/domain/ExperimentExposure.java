package com.bhukkad.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "experiment_exposures", indexes = {
        @Index(name = "idx_experiment_customer", columnList = "customerId, experiment")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class ExperimentExposure {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false, length = 100)
    private String experiment;
    @Column(nullable = false, length = 50)
    private String variant;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}