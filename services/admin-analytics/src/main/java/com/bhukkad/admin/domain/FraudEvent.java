package com.bhukkad.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "fraud_events", indexes = {
        @Index(name = "idx_fraud_customer", columnList = "customerId, createdAt"),
        @Index(name = "idx_fraud_status", columnList = "status")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class FraudEvent {
    public static final String STATUS_REVIEW = "REVIEW";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_CLEARED = "CLEARED";

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
    private String details;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}