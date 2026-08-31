package com.bhukkad.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "affiliate_referrals", indexes = {
        @Index(name = "idx_affiliate_referrals_code", columnList = "affiliateCode")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class AffiliateReferral {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40)
    private String affiliateCode;
    private Long referredBy;
    private Long referredUser;
    @Column(nullable = false, length = 20)
    private String status;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}