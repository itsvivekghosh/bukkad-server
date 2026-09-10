package com.bhukkad.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_customer", columnList = "customerId, createdAt"),
        @Index(name = "idx_orders_restaurant", columnList = "restaurantId, createdAt"),
        @Index(name = "idx_orders_status", columnList = "status")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class Order {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_PLACED = "PLACED";
    /** Async saga (feature #3): order created + stock reserved, waiting on the payment verdict. */
    public static final String STATUS_AWAITING_PAYMENT = "AWAITING_PAYMENT";
    /** Async saga (feature #3): payment verdict was FAILED; stock release is unwinding via outbox. */
    public static final String STATUS_PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String STATUS_SCHEDULED = "SCHEDULED";
    public static final String STATUS_PREPARING = "PREPARING";
    public static final String STATUS_READY_FOR_PICKUP = "READY_FOR_PICKUP";
    public static final String STATUS_OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private Long restaurantId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(length = 30)
    private String orderNumber;

    @Column
    private LocalDateTime deliveredAt;

    @Column
    private LocalDateTime estimatedDeliveryAt;

    @Column
    private Double subtotal;

    @Column
    private Double deliveryFee;

    @Column
    private Double taxAmount;

    @Column
    private Double discountAmount = 0.0;

    @Column
    private Integer loyaltyPointsRedeemed = 0;

    @Column
    private Double tipAmount = 0.0;

    @Column
    private Long deliveryAddressId;

    /** Assigned rider (identity users.id) for the delivery lifecycle. */
    @Column
    private Long deliveryAgentId;

    @Column(length = 500)
    private String specialInstructions;

    @Column
    private Integer estimatedDeliveryTime;

    @Column
    private LocalDateTime scheduledAt;

    @Column
    private Integer liveEtaMinutes;

    @Column
    private LocalDateTime liveEtaAt;

    @Column
    private Double walletAmountUsed = 0.0;

    @Column(length = 20)
    private String fulfillmentType = "DELIVERY";

    @Column(length = 100)
    private String deviceId;

    @Column(length = 20)
    private String guestPhone;

    @Column(length = 500)
    private String giftMessage;

    @Column(length = 100)
    private String recipientName;

    @Column(length = 20)
    private String recipientPhone;

    @Column(length = 500)
    private String cancellationReason;

    @Column(length = 20)
    private String cancelledBy;

    @Column(name = "coupon_id")
    private Long couponId;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
