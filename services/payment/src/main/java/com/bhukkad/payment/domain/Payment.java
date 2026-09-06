package com.bhukkad.payment.domain;

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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_order", columnList = "orderId"),
        @Index(name = "idx_payments_customer", columnList = "customerId, createdAt"),
        @Index(name = "idx_payments_status", columnList = "status"),
        @Index(name = "idx_payment_transaction", columnList = "transactionId", unique = true),
        @Index(name = "idx_payment_purpose", columnList = "purpose")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class Payment {

    public static final String PURPOSE_ORDER = "ORDER";
    public static final String PURPOSE_WALLET_TOP_UP = "WALLET_TOP_UP";

    public static final String METHOD_CASH_ON_DELIVERY = "CASH_ON_DELIVERY";
    public static final String METHOD_CREDIT_CARD = "CREDIT_CARD";
    public static final String METHOD_DEBIT_CARD = "DEBIT_CARD";
    public static final String METHOD_UPI = "UPI";
    public static final String METHOD_WALLET = "WALLET";
    public static final String METHOD_NET_BANKING = "NET_BANKING";
    public static final String METHOD_BNPL = "BNPL";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SETTLED = "SETTLED";
    public static final String STATUS_REFUNDED = "REFUNDED";
    public static final String STATUS_FAILED = "FAILED";
    /** Internal saga charge (batch A contract): accepted, not yet captured. */
    public static final String STATUS_PROCESSING = "PROCESSING";
    /** Internal saga WALLET charge: accepted; the wallet debit is the caller's step. */
    public static final String STATUS_PENDING_WALLET = "PENDING_WALLET";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false, length = 20)
    private String purpose = PURPOSE_ORDER;

    @Column(nullable = false, length = 30)
    private String paymentMethod = METHOD_UPI;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal walletAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal gatewayAmount = BigDecimal.ZERO;

    @Column(length = 100)
    private String transactionId;

    @Column(length = 100)
    private String gatewayOrderId;

    @Column(length = 100)
    private String gatewayPaymentId;

    @Column(length = 100)
    private String providerRef;

    @Column(length = 100)
    private String idempotencyKey;

    @Column(length = 50)
    private String provider;

    @Column(columnDefinition = "TEXT")
    private String paymentGatewayResponse;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;
}
