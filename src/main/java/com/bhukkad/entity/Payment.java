package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payment_order", columnList = "order_id"),
        @Index(name = "idx_payment_transaction", columnList = "transactionId", unique = true),
        @Index(name = "idx_payment_method", columnList = "paymentMethod"),
        @Index(name = "idx_payment_status", columnList = "status"),
        @Index(name = "idx_payment_created_at", columnList = "createdAt"),
        @Index(name = "idx_payment_customer", columnList = "customer_id"),
        @Index(name = "idx_payment_purpose", columnList = "purpose")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"order", "customer"})
@EqualsAndHashCode(exclude = {"order", "customer"})
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentPurpose purpose = PaymentPurpose.ORDER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(nullable = false)
    private Double amount;

    @Column(nullable = false)
    private Double walletAmount = 0.0;

    @Column(nullable = false)
    private Double gatewayAmount = 0.0;

    private String transactionId;

    private String gatewayOrderId;

    private String gatewayPaymentId;

    private String idempotencyKey;

    private String paymentGatewayResponse;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    public enum PaymentPurpose {
        ORDER, WALLET_TOP_UP
    }

    public enum PaymentMethod {
        CASH_ON_DELIVERY, CREDIT_CARD, DEBIT_CARD,
        UPI, WALLET, NET_BANKING
    }

    public enum PaymentStatus {
        PENDING, COMPLETED, FAILED, REFUNDED
    }
}
