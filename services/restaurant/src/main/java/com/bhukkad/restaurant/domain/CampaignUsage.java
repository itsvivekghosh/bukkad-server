package com.bhukkad.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Per-customer redemption ledger for promotion campaigns (Batch 4 wave 2
 * migration).
 *
 * <p>Restaurant-service port of the monolith
 * {@code com.bhukkad.entity.CampaignUsage}. Customer and order live in other
 * services, so the service-local variant stores plain {@code customerId} /
 * {@code orderId} columns (no cross-service FKs) instead of the monolith's
 * JPA relations. The monolith keeps a working copy until the gateway flips.
 */
@Entity
@Table(name = "campaign_usages", indexes = {
        @Index(name = "idx_usage_campaign", columnList = "campaign_id"),
        @Index(name = "idx_usage_customer", columnList = "campaign_id, customer_id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class CampaignUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "order_id")
    private Long orderId;

    @CreatedDate
    @Column(name = "used_at", nullable = false, updatable = false)
    private LocalDateTime usedAt;
}
