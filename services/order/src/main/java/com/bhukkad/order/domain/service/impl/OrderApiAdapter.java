package com.bhukkad.order.domain.service.impl;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import com.bhukkad.order.api.OrderOwnershipPort;
import com.bhukkad.order.api.OrderQueryPort;
import com.bhukkad.order.api.dto.response.OrderSummary;

/**
 * Adapter implementing the {@link OrderQueryPort} and {@link OrderOwnershipPort}
 * contracts on top of the order domain's repository. The only class other
 * domains should wire in for order reads.
 *
 * <p>Runs inside a read-only transaction so lazy associations resolve during
 * projection mapping instead of failing in the caller's session-less context.</p>
 */
@Service
@RequiredArgsConstructor
public class OrderApiAdapter implements OrderQueryPort, OrderOwnershipPort {

    private final OrderRepository orderRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderSummary> findSummary(Long orderId) {
        return orderRepository.findById(orderId).map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderSummary requireSummary(Long orderId) {
        return findSummary(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOwnedByCustomer(Long orderId, Long customerId) {
        return orderRepository.findById(orderId)
                .map(order -> order.getCustomerId().equals(customerId))
                .orElse(false);
    }

    private OrderSummary toSummary(Order order) {
        return new OrderSummary(
                order.getId(),
                order.getOrderNumber(),
                order.getCustomerId(),
                order.getRestaurantId(),
                null,
                order.getStatus(),
                order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO,
                order.getSubtotal() != null ? BigDecimal.valueOf(order.getSubtotal()) : null,
                order.getTipAmount() != null ? BigDecimal.valueOf(order.getTipAmount()) : null,
                order.getLoyaltyPointsRedeemed(),
                null,
                order.getCreatedAt(),
                order.getScheduledAt(),
                order.getLiveEtaMinutes(),
                order.getLiveEtaAt());
    }
}
