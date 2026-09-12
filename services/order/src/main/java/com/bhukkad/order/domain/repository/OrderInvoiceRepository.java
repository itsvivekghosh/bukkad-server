package com.bhukkad.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import com.bhukkad.order.domain.entity.OrderInvoice;

public interface OrderInvoiceRepository extends JpaRepository<OrderInvoice, Long> {
    Optional<OrderInvoice> findByOrderId(Long orderId);
}