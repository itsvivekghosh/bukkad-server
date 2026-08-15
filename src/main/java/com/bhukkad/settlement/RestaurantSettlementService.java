package com.bhukkad.settlement;

import com.bhukkad.config.SettlementProperties;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.dto.response.RestaurantSettlementResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantSettlement;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.RestaurantSettlementRepository;
import com.bhukkad.util.PaginationUtils;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RestaurantSettlementService {

    private final RestaurantSettlementRepository settlementRepository;
    private final RestaurantRepository restaurantRepository;
    private final SettlementProperties settlementProperties;

    @Transactional
    public void recordSettlementForDeliveredOrder(Order order) {
        if (settlementRepository.existsByOrderId(order.getId())) {
            return;
        }
        double orderAmount = order.getSubtotal() != null ? order.getSubtotal() : order.getTotalAmount();
        double commissionPercent = resolveCommissionPercent(order.getRestaurant());
        double commission = PriceCalculator.roundToTwoDecimals(orderAmount * commissionPercent / 100.0);
        double net = PriceCalculator.roundToTwoDecimals(orderAmount - commission);

        RestaurantSettlement settlement = new RestaurantSettlement();
        settlement.setRestaurant(order.getRestaurant());
        settlement.setOrder(order);
        settlement.setOrderAmount(orderAmount);
        settlement.setCommissionAmount(commission);
        settlement.setNetAmount(net);
        settlement.setStatus(RestaurantSettlement.SettlementStatus.PENDING);
        settlementRepository.save(settlement);
    }

    @Transactional(readOnly = true)
    public PagedResponse<RestaurantSettlementResponse> getRestaurantSettlements(Long restaurantId, int page, int size) {
        var result = settlementRepository.findByRestaurantIdOrderByCreatedAtDesc(
                restaurantId,
                PaginationUtils.page(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(result.map(this::toResponse));
    }

    @Transactional
    public int settlePendingForRestaurant(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        List<RestaurantSettlement> pending = settlementRepository.findByRestaurantIdAndStatus(
                restaurant.getId(), RestaurantSettlement.SettlementStatus.PENDING);
        LocalDateTime now = LocalDateTime.now();
        for (RestaurantSettlement settlement : pending) {
            settlement.setStatus(RestaurantSettlement.SettlementStatus.SETTLED);
            settlement.setSettledAt(now);
        }
        settlementRepository.saveAll(pending);
        return pending.size();
    }

    @Transactional(readOnly = true)
    public double getPendingSettlementAmount(Long restaurantId) {
        Double pending = settlementRepository.sumNetAmountByRestaurantAndStatus(
                restaurantId, RestaurantSettlement.SettlementStatus.PENDING);
        return pending != null ? pending : 0.0;
    }

    private RestaurantSettlementResponse toResponse(RestaurantSettlement settlement) {
        return RestaurantSettlementResponse.builder()
                .id(settlement.getId())
                .restaurantId(settlement.getRestaurant().getId())
                .orderId(settlement.getOrder().getId())
                .orderNumber(settlement.getOrder().getOrderNumber())
                .orderAmount(settlement.getOrderAmount())
                .commissionAmount(settlement.getCommissionAmount())
                .netAmount(settlement.getNetAmount())
                .status(settlement.getStatus().name())
                .settledAt(settlement.getSettledAt() != null ? settlement.getSettledAt().toString() : null)
                .createdAt(settlement.getCreatedAt() != null ? settlement.getCreatedAt().toString() : null)
                .build();
    }

    private double resolveCommissionPercent(Restaurant restaurant) {
        if (restaurant.getCommissionPercent() != null) {
            return restaurant.getCommissionPercent();
        }
        return settlementProperties.getCommissionPercent();
    }
}
