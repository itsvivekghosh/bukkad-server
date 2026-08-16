package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * GST tax invoice for a delivered order.
 *
 * <p>All monetary fields are snapshots taken at invoice generation time and are
 * never recomputed on read, so the values returned here always match the PDF and
 * the stored accounting record.
 *
 * <p>Tax is always split as CGST + SGST (intra-state supply); IGST is not
 * modelled because delivery is local to the restaurant's state.
 */
@Data
@Builder
public class OrderInvoiceResponse {
    private Long id;
    private Long orderId;
    private String orderNumber;
    private String invoiceNumber;
    private Double subtotal;
    private Double deliveryFee;
    private Double taxAmount;
    private Double cgstAmount;
    private Double sgstAmount;
    private Double discountAmount;
    private Double totalAmount;
    private String restaurantGstin;
    private String issuedAt;

    /**
     * Short-lived presigned URL for the stored PDF, or {@code null} when object
     * storage is disabled. When null, clients should use
     * {@code GET /api/v1/orders/{orderId}/invoice/pdf}, which renders on demand.
     */
    private String pdfUrl;

    /** True when a PDF copy exists in object storage. */
    private Boolean pdfAvailable;

    /** Timestamp the invoice PDF was emailed to the customer, if it was. */
    private String emailedAt;
}
