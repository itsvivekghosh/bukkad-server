package com.bhukkad.delivery;

import com.bhukkad.config.RiderEarningsProperties;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.RiderEarning;
import com.bhukkad.repository.RiderEarningRepository;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RiderEarningService {

    private final RiderEarningRepository riderEarningRepository;
    private final RiderEarningsProperties riderEarningsProperties;

    @Transactional
    public void recordDeliveryEarning(Order order, DeliveryAgent agent) {
        if (riderEarningRepository.existsByOrderId(order.getId())) {
            return;
        }
        RiderEarning earning = new RiderEarning();
        earning.setAgent(agent);
        earning.setOrder(order);
        earning.setAmount(PriceCalculator.roundToTwoDecimals(
                riderEarningsProperties.getPerDelivery()
                        + (order.getTipAmount() != null ? order.getTipAmount() : 0.0)));
        earning.setStatus(RiderEarning.EarningStatus.PENDING);
        riderEarningRepository.save(earning);
    }
}
