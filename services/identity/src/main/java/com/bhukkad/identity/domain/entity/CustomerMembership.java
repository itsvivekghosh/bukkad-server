package com.bhukkad.identity.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Port of the monolith {@code com.bhukkad.entity.CustomerMembership} (WAVE 2).
 * Name and {@code @Table} are unchanged; the slim WAVE 1 validity columns
 * (started_at/expires_at) are superseded by the monolith columns
 * (starts_at/ends_at) in {@code V7__membership_plan_and_affiliate_depth.sql}.
 */
@Entity
@Table(name = "customer_memberships", indexes = {
        @Index(name = "idx_membership_customer", columnList = "customerId, status")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class CustomerMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private MembershipPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status = MembershipStatus.ACTIVE;

    @Column(nullable = false)
    private LocalDateTime startsAt;

    @Column(nullable = false)
    private LocalDateTime endsAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum MembershipStatus {
        ACTIVE, EXPIRED, CANCELLED
    }
}
