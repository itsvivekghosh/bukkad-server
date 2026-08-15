package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * GST invoice issued for a delivered order.
 *
 * <p>One invoice exists per order (enforced by a unique constraint on
 * {@code order_id}). The monetary columns are snapshotted at generation time so
 * later price or coupon changes never alter an already-issued invoice.</p>
 *
 * <p>The {@code pdf*} and {@code email*} columns (added in migration
 * {@code V17__trust_and_compliance.sql}) track the rendered PDF artifact and the
 * customer email delivery attempt. They are all nullable because an invoice row
 * is created first and the PDF/email side effects happen afterwards, so a
 * failure to render or send never blocks invoice creation.</p>
 */
@Entity
@Table(name = "order_invoices")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(nullable = false, unique = true, length = 40)
    private String invoiceNumber;

    @Column(nullable = false)
    private Double subtotal;

    @Column(nullable = false)
    private Double deliveryFee = 0.0;

    @Column(nullable = false)
    private Double taxAmount = 0.0;

    @Column(nullable = false)
    private Double cgstAmount = 0.0;

    @Column(nullable = false)
    private Double sgstAmount = 0.0;

    @Column(nullable = false)
    private Double discountAmount = 0.0;

    @Column(nullable = false)
    private Double totalAmount;

    @Column(length = 20)
    private String restaurantGstin;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    /**
     * Object-storage key of the rendered PDF. {@code null} when object storage
     * is disabled, in which case the PDF is rendered on demand instead.
     */
    @Column(length = 512)
    private String pdfStorageKey;

    /** When the PDF was rendered; {@code null} means no PDF exists yet. */
    private LocalDateTime pdfGeneratedAt;

    /** When the invoice email was accepted by the mail sender. */
    private LocalDateTime emailedAt;

    /**
     * Address the invoice was emailed to, snapshotted because the customer's
     * email address may change after the invoice is issued.
     */
    @Column(length = 255)
    private String emailRecipient;

    /** Number of email send attempts; used to cap retries. */
    @Column(nullable = false)
    private Integer emailAttempts = 0;
}
