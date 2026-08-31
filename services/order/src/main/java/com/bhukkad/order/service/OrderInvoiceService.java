package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderInvoice;
import com.bhukkad.order.domain.OrderInvoiceRepository;
import com.bhukkad.order.domain.OrderRepository;
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
        if (invoiceRepository.findByOrderId(orderId).isPresent()) {
            return invoiceRepository.findByOrderId(orderId).orElseThrow();
        }
        BigDecimal gst = order.getTotalAmount().multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);
        OrderInvoice invoice = new OrderInvoice();
        invoice.setOrderId(orderId);
        invoice.setInvoiceNumber("INV-" + orderId);
        invoice.setGstAmount(gst);
        invoice.setTotal(order.getTotalAmount().add(gst));
        return invoiceRepository.save(invoice);
    }

    @Transactional(readOnly = true)
    public OrderInvoice getByOrder(Long orderId) {
        return invoiceRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found for order: " + orderId));
    }
}