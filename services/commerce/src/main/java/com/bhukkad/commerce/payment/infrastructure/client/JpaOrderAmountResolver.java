package com.bhukkad.commerce.payment.infrastructure.client;

import com.bhukkad.commerce.order.domain.repository.OrderRepository;
import com.bhukkad.commerce.payment.domain.service.OrderAmountResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Order-module adapter for {@link OrderAmountResolver}. Reads
 * {@code orders.total_amount}, which OrderService computes server-side from
 * re-priced menu lines — never from the client.
 */
@Component
public class JpaOrderAmountResolver implements OrderAmountResolver {

    private final OrderRepository orderRepository;

    public JpaOrderAmountResolver(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Optional<BigDecimal> authoritativeTotal(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return orderRepository.findById(orderId)
                .map(order -> order.getTotalAmount())
                .filter(total -> total != null && total.signum() > 0);
    }
}