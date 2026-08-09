package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payment_order", columnList = "order_id", unique = true),
        @Index(name = "idx_payment_transaction", columnList = "transactionId", unique = true),
        @Index(name = "idx_payment_method", columnList = "paymentMethod"),
        @Index(name = "idx_payment_status", columnList = "status"),
        @Index(name = "idx_payment_created_at", columnList = "createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(nullable = false)
    private Double amount;

    private String transactionId;

    private String paymentGatewayResponse;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    public enum PaymentMethod {
        CASH_ON_DELIVERY, CREDIT_CARD, DEBIT_CARD,
        UPI, WALLET, NET_BANKING
    }

    public enum PaymentStatus {
        PENDING, COMPLETED, FAILED, REFUNDED
    }
}