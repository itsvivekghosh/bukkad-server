package com.bhukkad.order.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.entity.OrderInvoice;
import com.bhukkad.order.domain.repository.OrderInvoiceRepository;
import com.bhukkad.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * GST invoice generation per delivered order (Batch B depth). One invoice per
 * order (unique order_id); regenerating is idempotent (returns the existing).
 */
@Service
@RequiredArgsConstructor
public class OrderInvoiceService {

    public static final BigDecimal GST_RATE = new BigDecimal("0.18");

    private final OrderRepository orderRepository;
    private final OrderInvoiceRepository invoiceRepository;

    @Transactional
    public OrderInvoice generate(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        return invoiceRepository.findByOrderId(orderId).orElseGet(() -> {
            BigDecimal subtotal = bd(order.getSubtotal());
            BigDecimal deliveryFee = bd(order.getDeliveryFee());
            BigDecimal discount = bd(order.getDiscountAmount());
            // The order's taxAmount already includes GST when set; fall back to the
            // standard 18 % computation (monolith parity via PriceCalculator rules).
            BigDecimal tax = order.getTaxAmount() != null
                    ? bd(order.getTaxAmount())
                    : subtotal.multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cgst = tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            BigDecimal sgst = tax.subtract(cgst);
            BigDecimal total = order.getTotalAmount() != null
                    ? order.getTotalAmount()
                    : subtotal.add(deliveryFee).add(tax).subtract(discount).setScale(2, RoundingMode.HALF_UP);

            OrderInvoice invoice = new OrderInvoice();
            invoice.setOrderId(orderId);
            invoice.setInvoiceNumber("INV-" + orderId);
            invoice.setSubtotal(subtotal);
            invoice.setDeliveryFee(deliveryFee);
            invoice.setTaxAmount(tax);
            invoice.setCgstAmount(cgst);
            invoice.setSgstAmount(sgst);
            invoice.setDiscountAmount(discount);
            invoice.setTotalAmount(total);
            invoice.setIssuedAt(java.time.LocalDateTime.now());
            return invoiceRepository.save(invoice);
        });
    }

    private static BigDecimal bd(Double value) {
        return value != null ? BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    @Transactional(readOnly = true)
    public OrderInvoice getByOrder(Long orderId) {
        return invoiceRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found for order: " + orderId));
    }
}