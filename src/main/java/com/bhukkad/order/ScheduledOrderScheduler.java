package com.bhukkad.order;

import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.service.OrderService;
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
public class ScheduledOrderScheduler {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final OrderEventPublisher orderEventPublisher;

    @Scheduled(fixedDelayString = "${app.scheduled-orders.scheduler-interval-ms:60000}")
    @Transactional
    public void processScheduledOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<Order> dueOrders = orderRepository.findByStatusAndScheduledAtLessThanEqual(
                Order.OrderStatus.SCHEDULED, now);

        if (dueOrders.isEmpty()) {
            return;
        }

        log.info("Processing {} scheduled orders due at or before {}", dueOrders.size(), now);

        for (Order order : dueOrders) {
            try {
                processScheduledOrder(order);
            } catch (Exception ex) {
                log.error("Failed to process scheduled order {}: {}", order.getId(), ex.getMessage(), ex);
            }
        }
    }

    private void processScheduledOrder(Order order) {
        Order.OrderStatus previousStatus = order.getStatus();
        order.setStatus(Order.OrderStatus.PLACED);
        order.setScheduledAt(null);
        order = orderRepository.save(order);

        orderEventPublisher.publishStatusChange(order, previousStatus);

        log.info("Scheduled order {} transitioned to PLACED", order.getId());
    }
}