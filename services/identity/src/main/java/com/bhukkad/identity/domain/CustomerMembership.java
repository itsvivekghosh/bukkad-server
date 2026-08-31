package com.bhukkad.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_memberships", indexes = {
        @Index(name = "idx_membership_customer", columnList = "customerId, status")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class CustomerMembership {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false)
    private Long planId;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(nullable = false)
    private LocalDateTime startedAt;
    @Column(nullable = false)
    private LocalDateTime expiresAt;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}