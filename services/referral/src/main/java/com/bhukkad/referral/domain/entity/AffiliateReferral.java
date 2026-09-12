package com.bhukkad.referral.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One customer signup attributed to an affiliate code. The link to the
 * affiliate code is relational (same service); the customer is referenced by
 * id + denormalized email for the admin stats view.
 */
@Entity
@Table(name = "affiliate_referrals", indexes = {
        @Index(name = "idx_affiliate_referral_code", columnList = "affiliate_code_id"),
        @Index(name = "idx_affiliate_referral_status", columnList = "status"),
        @Index(name = "idx_affiliate_referral_customer", columnList = "customer_id")
})
public class AffiliateReferral {

    public enum AffiliateReferralStatus {
        PENDING, PAID, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "affiliate_code_id", nullable = false)
    private AffiliateCode affiliateCode;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_email", length = 200)
    private String customerEmail;

    @Column(nullable = false)
    private Double rewardAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AffiliateReferralStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public AffiliateCode getAffiliateCode() {
        return affiliateCode;
    }

    public void setAffiliateCode(AffiliateCode affiliateCode) {
        this.affiliateCode = affiliateCode;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public Double getRewardAmount() {
        return rewardAmount;
    }

    public void setRewardAmount(Double rewardAmount) {
        this.rewardAmount = rewardAmount;
    }

    public AffiliateReferralStatus getStatus() {
        return status;
    }

    public void setStatus(AffiliateReferralStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}