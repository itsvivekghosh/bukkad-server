package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * A registrable influencer/affiliate code for the Affiliate/Referral Program.
 *
 * <p>Customers can sign up using an affiliate code; each signup is recorded in
 * {@link AffiliateReferral} so referral volume and reward liability are trackable
 * per code/channel.</p>
 */
@Entity
@Table(name = "affiliate_codes", indexes = {
        @Index(name = "uk_affiliate_code", columnList = "code", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class AffiliateCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 40)
    private String channel;

    @Column(nullable = false)
    private Double rewardAmount = 0.0;

    @Column(nullable = false)
    private Boolean isActive = true;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
