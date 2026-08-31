package com.bhukkad.order.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_invoices")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class OrderInvoice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long orderId;
    @Column(nullable = false, length = 50)
    private String invoiceNumber;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal gstAmount = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}