package com.bhukkad.order.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderInvoiceResponse {
    private Long id;
    private Long orderId;
    private String invoiceNumber;
    private BigDecimal subtotal;
    private BigDecimal deliveryFee;
    private BigDecimal taxAmount;
    private BigDecimal cgstAmount;
    private BigDecimal sgstAmount;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;
    private String restaurantGstin;
    private LocalDateTime issuedAt;
    private String pdfStorageKey;
    private LocalDateTime pdfGeneratedAt;
    private LocalDateTime emailedAt;
    private String emailRecipient;
    private Integer emailAttempts;
}
