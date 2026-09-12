package com.bhukkad.order.domain.entity;

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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_invoices", indexes = {
        @Index(name = "uk_order_invoice_number", columnList = "invoiceNumber", unique = true)
})
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
    private BigDecimal subtotal;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryFee = BigDecimal.ZERO;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal cgstAmount = BigDecimal.ZERO;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal sgstAmount = BigDecimal.ZERO;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;
    @Column(length = 20)
    private String restaurantGstin;
    @Column(nullable = false)
    private LocalDateTime issuedAt;
    @Column(length = 512)
    private String pdfStorageKey;
    private LocalDateTime pdfGeneratedAt;
    private LocalDateTime emailedAt;
    @Column(length = 255)
    private String emailRecipient;
    @Column(nullable = false)
    private Integer emailAttempts = 0;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
