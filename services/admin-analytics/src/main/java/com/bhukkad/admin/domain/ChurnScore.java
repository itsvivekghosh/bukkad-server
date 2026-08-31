package com.bhukkad.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "churn_scores", indexes = {
        @Index(name = "idx_churn_customer", columnList = "customerId, computedAt")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class ChurnScore {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false)
    private Double score;
    @Column(nullable = false, length = 20)
    private String modelVersion;
    private String featuresJson;
    @Column(nullable = false)
    private LocalDateTime computedAt;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}