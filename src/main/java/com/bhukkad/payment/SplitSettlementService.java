package com.bhukkad.payment;

import com.bhukkad.config.RiderEarningsProperties;
import com.bhukkad.config.SettlementProperties;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.RestaurantSettlement;
import com.bhukkad.entity.RiderEarning;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.repository.RestaurantSettlementRepository;
import com.bhukkad.repository.RiderEarningRepository;
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
    private final RiderEarningRepository riderEarningRepository;
    private final SettlementProperties settlementProperties;
    private final RiderEarningsProperties riderEarningsProperties;

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
        if (order.getDeliveryAgent() != null
                && !riderEarningRepository.existsByOrderId(order.getId())) {
            recordRiderEarning(order);
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

    private void recordRiderEarning(Order order) {
        double base = riderEarningsProperties.getPerDelivery();
        double bonus = order.getTipAmount() != null ? order.getTipAmount() : 0.0;
        double amount = PriceCalculator.roundToTwoDecimals(base + bonus);

        RiderEarning earning = new RiderEarning();
        earning.setAgent(order.getDeliveryAgent());
        earning.setOrder(order);
        earning.setAmount(amount);
        earning.setStatus(RiderEarning.EarningStatus.PENDING);
        riderEarningRepository.save(earning);
    }
}
