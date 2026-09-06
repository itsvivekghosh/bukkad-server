package com.bhukkad.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GiftOrderRepository extends JpaRepository<GiftOrder, Long> {
    Optional<GiftOrder> findByOrderId(Long orderId);
}
