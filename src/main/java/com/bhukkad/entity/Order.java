package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
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
@Data
@NoArgsConstructor
@AllArgsConstructor
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

    @ManyToOne
    @JoinColumn(name = "coupon_id")
    private Coupon appliedCoupon;

    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL)
    private Payment payment;

    private String specialInstructions;

    private Boolean contactlessDelivery = false;

    private Integer estimatedDeliveryTime; // in minutes

    private LocalDateTime estimatedDeliveryAt;

    private LocalDateTime deliveredAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public enum OrderStatus {
        PLACED, CONFIRMED, PREPARING, READY_FOR_PICKUP,
        OUT_FOR_DELIVERY, DELIVERED, CANCELLED, REFUNDED
    }
}