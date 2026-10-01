package com.bhukkad.commerce.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import com.bhukkad.commerce.order.domain.entity.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByOrderId(Long orderId);

    List<OrderItem> findByOrderIdIn(List<Long> orderIds);
}
