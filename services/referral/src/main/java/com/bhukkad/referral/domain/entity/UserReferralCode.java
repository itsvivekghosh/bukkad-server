package com.bhukkad.referral.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * A customer's personal referral code plus aggregate counters.
 *
 * <p>Denormalized: the referral bonus amounts shown here are tracked in this
 * service as summary rows (the wallet credit itself is performed by the
 * wallet domain when the referee's first order completes). {@code referredBy}
 * is a plain customer id — no cross-service JPA joins.</p>
 */
@Entity
@Table(name = "user_referral_codes", uniqueConstraints = @UniqueConstraint(
        name = "uk_referral_code", columnNames = {"referralCode"}),
        indexes = {
                @Index(name = "idx_referral_customer", columnList = "customerId"),
                @Index(name = "idx_referral_referred_by", columnList = "referredBy")
        })
public class UserReferralCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "referral_code", nullable = false, length = 40)
    private String referralCode;

    @Column(name = "referredBy")
    private Long referredBy;

    @Column(name = "referrals_count", nullable = false)
    private int referralsCount;

    @Column(name = "referral_bonus_earned", nullable = false)
    private double referralBonusEarned;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getReferralCode() {
        return referralCode;
    }

    public void setReferralCode(String referralCode) {
        this.referralCode = referralCode;
    }

    public Long getReferredBy() {
        return referredBy;
    }

    public void setReferredBy(Long referredBy) {
        this.referredBy = referredBy;
    }

    public int getReferralsCount() {
        return referralsCount;
    }

    public void setReferralsCount(int referralsCount) {
        this.referralsCount = referralsCount;
    }

    public double getReferralBonusEarned() {
        return referralBonusEarned;
    }

    public void setReferralBonusEarned(double referralBonusEarned) {
        this.referralBonusEarned = referralBonusEarned;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}