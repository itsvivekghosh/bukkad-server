package com.bhukkad.referral.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Append-only referral reward ledger row (ADR-005 / audit feature #4): the
 * durable, exactly-once record of a credited referral reward.
 *
 * <p>Idempotency: the {@code (event_type, event_id)} unique constraint makes
 * a replayed apply/complete event (including the same {@code orderId}) land
 * on this table at most once; {@code (referred_customer_id, reward_type)}
 * caps each referee at one reward of each type — ever.</p>
 */
@Entity
@Table(name = "referral_rewards_ledger")
public class ReferralRewardLedger {

    public static final String TYPE_APPLY_BONUS = "APPLY_BONUS";
    public static final String TYPE_COMPLETION_BONUS = "COMPLETION_BONUS";

    public static final String EVENT_APPLY = "REFERRAL_APPLY";
    public static final String EVENT_COMPLETE = "REFERRAL_COMPLETE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receiver_customer_id", nullable = false)
    private Long receiverCustomerId;

    @Column(name = "referred_customer_id", nullable = false)
    private Long referredCustomerId;

    @Column(name = "reward_type", nullable = false, length = 30)
    private String rewardType;

    @Column(name = "reward_amount", nullable = false)
    private double rewardAmount;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "event_id", nullable = false, length = 80)
    private String eventId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static ReferralRewardLedger of(Long receiverCustomerId, Long referredCustomerId,
                                          String rewardType, double rewardAmount,
                                          String eventType, String eventId, Long orderId) {
        ReferralRewardLedger row = new ReferralRewardLedger();
        row.setReceiverCustomerId(receiverCustomerId);
        row.setReferredCustomerId(referredCustomerId);
        row.setRewardType(rewardType);
        row.setRewardAmount(rewardAmount);
        row.setEventType(eventType);
        row.setEventId(eventId);
        row.setOrderId(orderId);
        return row;
    }

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getReceiverCustomerId() {
        return receiverCustomerId;
    }

    public void setReceiverCustomerId(Long receiverCustomerId) {
        this.receiverCustomerId = receiverCustomerId;
    }

    public Long getReferredCustomerId() {
        return referredCustomerId;
    }

    public void setReferredCustomerId(Long referredCustomerId) {
        this.referredCustomerId = referredCustomerId;
    }

    public String getRewardType() {
        return rewardType;
    }

    public void setRewardType(String rewardType) {
        this.rewardType = rewardType;
    }

    public double getRewardAmount() {
        return rewardAmount;
    }

    public void setRewardAmount(double rewardAmount) {
        this.rewardAmount = rewardAmount;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
