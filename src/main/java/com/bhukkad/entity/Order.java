package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_order_number", columnList = "orderNumber", unique = true),
        @Index(name = "idx_order_customer", columnList = "customer_id"),
        @Index(name = "idx_order_restaurant", columnList = "restaurant_id"),
        @Index(name = "idx_order_delivery_agent", columnList = "delivery_agent_id"),
        @Index(name = "idx_order_status", columnList = "status"),
        @Index(name = "idx_order_created_at", columnList = "createdAt"),
        @Index(name = "idx_order_delivered_at", columnList = "deliveredAt"),
        @Index(name = "idx_order_customer_status", columnList = "customer_id, status"),
        @Index(name = "idx_order_restaurant_status", columnList = "restaurant_id, status"),
        @Index(name = "idx_order_agent_status", columnList = "delivery_agent_id, status"),
        @Index(name = "idx_order_customer_created", columnList = "customer_id, createdAt"),
        @Index(name = "idx_order_restaurant_created", columnList = "restaurant_id, createdAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"customer", "restaurant", "orderItems", "deliveryAddress", "deliveryAgent", "payment", "appliedCoupon"})
@EqualsAndHashCode(exclude = {"customer", "restaurant", "orderItems", "deliveryAddress", "deliveryAgent", "payment", "appliedCoupon"})
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItem> orderItems = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_address_id", nullable = false)
    private Address deliveryAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_agent_id")
    private DeliveryAgent deliveryAgent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PLACED;

    @Column(nullable = false)
    private Double subtotal;

    private Double deliveryFee;

    private Double taxAmount;

    private Double discountAmount;

    @Column(nullable = false)
    private Double totalAmount;

    @Column(nullable = false)
    private Integer loyaltyPointsRedeemed = 0;

    @Column(nullable = false)
    private Double walletAmountUsed = 0.0;

    @Column(nullable = false)
    private Double tipAmount = 0.0;

    @ManyToOne
    @JoinColumn(name = "coupon_id")
    private Coupon appliedCoupon;

    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL)
    private Payment payment;

    private String specialInstructions;

    private Boolean contactlessDelivery = false;

    private Integer estimatedDeliveryTime; // in minutes

    private LocalDateTime estimatedDeliveryAt;

    private LocalDateTime scheduledAt;

    private Integer liveEtaMinutes;

    private LocalDateTime liveEtaAt;

    private LocalDateTime deliveredAt;

    /** Reason provided when the order was cancelled. */
    private String cancellationReason;

    /** Role or actor that cancelled the order (e.g. CUSTOMER, ADMIN). */
    private String cancelledBy;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    public enum OrderStatus {
        SCHEDULED, PLACED, CONFIRMED, PREPARING, READY_FOR_PICKUP,
        OUT_FOR_DELIVERY, DELIVERED, CANCELLED, REFUNDED
    }
}