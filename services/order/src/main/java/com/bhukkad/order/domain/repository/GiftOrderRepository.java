package com.bhukkad.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import com.bhukkad.order.domain.entity.GiftOrder;

public interface GiftOrderRepository extends JpaRepository<GiftOrder, Long> {
    Optional<GiftOrder> findByOrderId(Long orderId);
}
