package com.bhukkad.delivery;

import com.bhukkad.event.OrderSettledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery-side consumer of settlement events. The payment domain publishes
 * {@link OrderSettledEvent} through the outbox; this listener records the
 * rider earning inside the delivery domain, which owns the aggregate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryEventListener {

    private final RiderEarningService riderEarningService;
    private final com.bhukkad.repository.OrderRepository orderRepository;
    private final com.bhukkad.repository.DeliveryAgentRepository deliveryAgentRepository;

    @EventListener
    @Transactional
    public void onOrderSettled(OrderSettledEvent event) {
        if (event.deliveryAgentId() == null) {
            return;
        }
        orderRepository.findById(event.orderId())
                .ifPresent(order -> deliveryAgentRepository.findById(event.deliveryAgentId())
                        .ifPresent(agent -> {
                            riderEarningService.recordDeliveryEarning(order, agent);
                            log.info("RIDER_EARNING_RECORDED | orderId={} | agentId={}",
                                    event.orderId(), event.deliveryAgentId());
                        }));
    }
}
