package com.bhukkad.support.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name="disputes", indexes={@Index(name="idx_dispute_order", columnList="order_id"), @Index(name="idx_dispute_status", columnList="status"), @Index(name="idx_dispute_created", columnList="createdAt"), @Index(name="idx_dispute_customer", columnList="customer_id")})
@EntityListeners(value={AuditingEntityListener.class})
public class Dispute {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;
    @Column(name="order_id", nullable=false)
    private Long orderId;
    @Column(name="customer_id", nullable=false)
    private Long customerId;
    @Enumerated(value=EnumType.STRING)
    @Column(nullable=false, length=20)
    private DisputeType type;
    @Enumerated(value=EnumType.STRING)
    @Column(nullable=false, length=20)
    private DisputeStatus status;
    @Column(columnDefinition="TEXT")
    private String customerEvidence;
    @Column(columnDefinition="TEXT")
    private String riderEvidence;
    @Column(columnDefinition="TEXT")
    private String restaurantEvidence;
    @Column(columnDefinition="TEXT")
    private String resolutionNotes;
    @Enumerated(value=EnumType.STRING)
    @Column(length=20)
    private DisputeResolution resolution;
    private Double refundAmount;
    @Column(name = "resolved_by")
    private Long resolvedById;
    private LocalDateTime resolvedAt;
    @CreatedDate
    @Column(name="created_at", nullable=false, updatable=false)
    private LocalDateTime createdAt;

    public Dispute() {
    }

    public Dispute(Long orderId, DisputeType type, DisputeStatus status, String customerEvidence, String riderEvidence, String restaurantEvidence, String resolutionNotes, DisputeResolution resolution, Double refundAmount, Long resolvedById, LocalDateTime resolvedAt, LocalDateTime createdAt) {
        this.orderId = orderId;
        this.type = type;
        this.status = status;
        this.customerEvidence = customerEvidence;
        this.riderEvidence = riderEvidence;
        this.restaurantEvidence = restaurantEvidence;
        this.resolutionNotes = resolutionNotes;
        this.resolution = resolution;
        this.refundAmount = refundAmount;
        this.resolvedById = resolvedById;
        this.resolvedAt = resolvedAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return this.orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getCustomerId() {
        return this.customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public DisputeType getType() {
        return this.type;
    }

    public void setType(DisputeType type) {
        this.type = type;
    }

    public DisputeStatus getStatus() {
        return this.status;
    }

    public void setStatus(DisputeStatus status) {
        this.status = status;
    }

    public String getCustomerEvidence() {
        return this.customerEvidence;
    }

    public void setCustomerEvidence(String customerEvidence) {
        this.customerEvidence = customerEvidence;
    }

    public String getRiderEvidence() {
        return this.riderEvidence;
    }

    public void setRiderEvidence(String riderEvidence) {
        this.riderEvidence = riderEvidence;
    }

    public String getRestaurantEvidence() {
        return this.restaurantEvidence;
    }

    public void setRestaurantEvidence(String restaurantEvidence) {
        this.restaurantEvidence = restaurantEvidence;
    }

    public String getResolutionNotes() {
        return this.resolutionNotes;
    }

    public void setResolutionNotes(String resolutionNotes) {
        this.resolutionNotes = resolutionNotes;
    }

    public DisputeResolution getResolution() {
        return this.resolution;
    }

    public void setResolution(DisputeResolution resolution) {
        this.resolution = resolution;
    }

    public Double getRefundAmount() {
        return this.refundAmount;
    }

    public void setRefundAmount(Double refundAmount) {
        this.refundAmount = refundAmount;
    }

    public Long getResolvedById() {
        return this.resolvedById;
    }

    public void setResolvedById(Long resolvedById) {
        this.resolvedById = resolvedById;
    }

    public LocalDateTime getResolvedAt() {
        return this.resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public LocalDateTime getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public static enum DisputeType {
        ORDER_NOT_RECEIVED,
        WRONG_ORDER,
        LATE_DELIVERY,
        FOOD_QUALITY,
        PAYMENT_ISSUE,
        OTHER;

    }

    public static enum DisputeStatus {
        OPEN,
        UNDER_REVIEW,
        AUTO_RESOLVED,
        MANUAL_RESOLVED,
        CLOSED;

    }

    public static enum DisputeResolution {
        FULL_REFUND,
        PARTIAL_REFUND,
        NO_REFUND,
        CREDIT_ISSUED,
        ESCALATED;

    }
}
