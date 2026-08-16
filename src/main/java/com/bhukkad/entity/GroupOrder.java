package com.bhukkad.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "group_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class GroupOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @ElementCollection
    @CollectionTable(name = "group_order_participants", joinColumns = @JoinColumn(name = "group_order_id"))
    @Column(name = "customer_id", nullable = false)
    private List<Long> participatingCustomers = new ArrayList<>();

    @Column(name = "primary_customer_id", nullable = false)
    private Long primaryCustomerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GroupOrderStatus status = GroupOrderStatus.PENDING;

    private Double subtotal;

    private Double deliveryFee;

    private Double taxAmount;

    private Double discountAmount;

    private Double totalAmount;

    private Double tipAmount;

    private String specialInstructions;

    private Boolean contactlessDelivery = false;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime confirmedAt;

    private LocalDateTime cancelledAt;

    private String cancellationReason;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private Payment.PaymentMethod paymentMethod = Payment.PaymentMethod.CASH_ON_DELIVERY;

    @Column(name = "loyalty_points_redeemed")
    private Integer loyaltyPointsRedeemed = 0;

    @Column(name = "wallet_amount_used")
    private Double walletAmountUsed = 0.0;

    public enum GroupOrderStatus {
        PENDING, CONFIRMED, PREPARING, READY_FOR_PICKUP,
        OUT_FOR_DELIVERY, DELIVERED, CANCELLED
    }
}