package com.bhukkad.admin.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "fraud_review_queue", indexes = {
        @Index(name = "idx_fraud_review_status", columnList = "status"),
        @Index(name = "idx_fraud_review_customer", columnList = "customerId")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class FraudReviewAction {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REVIEWED = "REVIEWED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false, length = 100)
    private String rule;
    @Column(nullable = false, length = 20)
    private String severity;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(length = 100)
    private String assignedTo;
    private String notes;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @LastModifiedDate @Column(nullable = false)
    private LocalDateTime updatedAt;
}