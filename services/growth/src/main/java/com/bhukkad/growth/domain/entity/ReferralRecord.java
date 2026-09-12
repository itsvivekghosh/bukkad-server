package com.bhukkad.growth.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Growth-side referral tracking row for the V1 baseline
 * {@code referral_records} table (previously unmapped). One row per referral
 * attempt; the partial unique index
 * {@code uq_referral_records_referred_customer (referred_customer_id) WHERE referred_customer_id IS NOT NULL}
 * (V10) guarantees a referred customer can never be re-bound for another
 * reward round (ADR-005 idempotent apply).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "referral_records")
public class ReferralRecord {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "referrer_id", nullable = false)
    private Long referrerId;

    @Column(name = "referred_customer_id")
    private Long referredCustomerId;

    @Column(name = "referral_code", nullable = false, length = 20)
    private String referralCode;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "reward_credited")
    private boolean rewardCredited;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
