package com.bhukkad.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderInvoiceRepository extends JpaRepository<OrderInvoice, Long> {
    Optional<OrderInvoice> findByOrderId(Long orderId);
}