package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * One signup attributed to an affiliate code (Affiliate/Referral Program tracking).
 */
@Entity
@Table(name = "affiliate_referrals", indexes = {
        @Index(name = "idx_affiliate_referral_code", columnList = "affiliate_code_id"),
        @Index(name = "idx_affiliate_referral_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class AffiliateReferral {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "affiliate_code_id", nullable = false)
    private AffiliateCode affiliateCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false)
    private Double rewardAmount = 0.0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AffiliateReferralStatus status = AffiliateReferralStatus.PENDING;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum AffiliateReferralStatus {
        PENDING,
        PAID,
        VOID
    }
}
