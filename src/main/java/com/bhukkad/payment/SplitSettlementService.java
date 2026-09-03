package com.bhukkad.payment;

import com.bhukkad.config.SettlementProperties;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.RestaurantSettlement;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.repository.RestaurantSettlementRepository;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the split settlement for a delivered order: platform commission and
 * restaurant net as a {@link RestaurantSettlement}, plus the rider's earnings
 * as a {@link RiderEarning}, both in PENDING state. Idempotent per order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SplitSettlementService {

    private final RestaurantSettlementRepository restaurantSettlementRepository;
    private final SettlementProperties settlementProperties;
    private final com.bhukkad.common.outbox.OutboxEventService outboxEventService;

    @Transactional
    public void settle(Order order) {
        if (order == null || order.getId() == null) {
            throw new BusinessException("Order is required for settlement");
        }
        boolean recorded = false;
        if (!restaurantSettlementRepository.existsByOrderId(order.getId())) {
            recordRestaurantSettlement(order);
            recorded = true;
        }
        // Rider earnings are the delivery domain's aggregate: publish the
        // settlement through the outbox (atomic with this transaction) and let
        // the delivery listener record it. The payment domain never writes
        // delivery tables directly. Only publish when the settlement was newly
        // recorded — re-settling must not re-trigger downstream work.
        if (recorded && order.getDeliveryAgent() != null) {
            outboxEventService.enqueue("ORDER_SETTLED", order.getId(),
                    new com.bhukkad.event.OrderSettledEvent(
                            order.getId(),
                            order.getOrderNumber(),
                            order.getRestaurant() != null ? order.getRestaurant().getId() : null,
                            order.getDeliveryAgent().getId(),
                            order.getTipAmount(),
                            java.time.LocalDateTime.now()));
            recorded = true;
        }
        log.info("Split settlement processed | orderId={} | recorded={}", order.getId(), recorded);
    }

    private void recordRestaurantSettlement(Order order) {
        double orderAmount = order.getTotalAmount() != null
                ? order.getTotalAmount()
                : (order.getSubtotal() != null ? order.getSubtotal() : 0.0);
        double subtotal = order.getSubtotal() != null ? order.getSubtotal() : orderAmount;
        double commission = PriceCalculator.roundToTwoDecimals(
                orderAmount * settlementProperties.getCommissionPercent() / 100.0);
        double net = PriceCalculator.roundToTwoDecimals(subtotal - commission);

        RestaurantSettlement settlement = new RestaurantSettlement();
        settlement.setRestaurant(order.getRestaurant());
        settlement.setOrder(order);
        settlement.setOrderAmount(orderAmount);
        settlement.setCommissionAmount(commission);
        settlement.setNetAmount(net);
        settlement.setStatus(RestaurantSettlement.SettlementStatus.PENDING);
        restaurantSettlementRepository.save(settlement);
    }

    /** Delivery-side listener records the earning; see DeliveryEventListener. */
}
