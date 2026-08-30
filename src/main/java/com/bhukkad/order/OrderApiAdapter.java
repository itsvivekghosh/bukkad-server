package com.bhukkad.order;

import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.order.api.OrderOwnershipPort;
import com.bhukkad.order.api.OrderQueryPort;
import com.bhukkad.order.api.OrderSummary;
import com.bhukkad.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Adapter implementing the {@link OrderQueryPort} and {@link OrderOwnershipPort}
 * contracts on top of the order domain's repository. The only class other
 * domains should wire in for order reads.
 *
 * <p>Runs inside a read-only transaction so lazy associations (customer,
 * delivery address) resolve during projection mapping instead of failing in
 * the caller's session-less context.</p>
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
                .map(order -> order.getCustomer().getId().equals(customerId))
                .orElse(false);
    }

    private OrderSummary toSummary(com.bhukkad.entity.Order order) {
        OrderSummary.AddressBrief address = null;
        if (order.getDeliveryAddress() != null) {
            var a = order.getDeliveryAddress();
            address = new OrderSummary.AddressBrief(
                    a.getId(), a.getAddressLine1(), a.getCity(), a.getPincode(), a.getLatitude(), a.getLongitude());
        }
        return new OrderSummary(
                order.getId(),
                order.getOrderNumber(),
                order.getCustomer() != null ? order.getCustomer().getId() : null,
                order.getRestaurant() != null ? order.getRestaurant().getId() : null,
                order.getDeliveryAgent() != null ? order.getDeliveryAgent().getId() : null,
                order.getStatus() != null ? order.getStatus().name() : null,
                bd(order.getTotalAmount()),
                bd(order.getSubtotal()),
                bd(order.getTipAmount()),
                order.getLoyaltyPointsRedeemed(),
                address,
                order.getCreatedAt(),
                order.getScheduledAt(),
                order.getLiveEtaMinutes(),
                order.getLiveEtaAt());
    }

    private static java.math.BigDecimal bd(Double value) {
        return value == null ? null : java.math.BigDecimal.valueOf(value);
    }
}
