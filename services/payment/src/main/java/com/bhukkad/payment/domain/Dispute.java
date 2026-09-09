package com.bhukkad.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "disputes", indexes = {
        @Index(name = "idx_disputes_payment", columnList = "paymentId"),
        @Index(name = "idx_disputes_customer", columnList = "customerId, status")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class Dispute {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_REFUNDED = "REFUNDED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long paymentId;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false)
    private Long orderId;
    @Column(nullable = false, length = 100)
    private String reason;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    @Column(length = 255)
    private String resolution;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @LastModifiedDate @Column(nullable = false)
    private LocalDateTime updatedAt;
}