package com.bhukkad.order;

import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledOrderProcessor {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderEtaService orderEtaService;

    @Scheduled(fixedDelayString = "${app.scheduled-orders.dispatch-interval-ms:60000}")
    @Transactional
    public void dispatchDueOrders() {
        List<Order> due = orderRepository.findByStatusAndScheduledAtLessThanEqual(
                Order.OrderStatus.SCHEDULED, LocalDateTime.now());
        for (Order order : due) {
            Order.OrderStatus previous = order.getStatus();
            order.setStatus(Order.OrderStatus.PLACED);
            orderEtaService.applyLiveEta(order);
            orderRepository.save(order);
            orderEventPublisher.publishStatusChange(order, previous);
            log.info("Scheduled order dispatched | orderId={} | orderNumber={}", order.getId(), order.getOrderNumber());
        }
    }
}
