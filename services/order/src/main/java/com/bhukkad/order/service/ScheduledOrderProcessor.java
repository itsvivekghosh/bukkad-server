package com.bhukkad.order.service;

import com.bhukkad.common.util.Constants;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.service.OrderEventPublisher;
import com.bhukkad.order.domain.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Promotes due {@code SCHEDULED} orders to {@code PLACED}. This is the
 * single poller for scheduled orders.
 *
 * <p>ShedLock (single-runner across replicas) plus the
 * {@code SCHEDULED -> PLACED} status guard make dispatch idempotent under
 * horizontal scaling; a failure on one order is contained so the rest of the
 * batch still dispatches.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledOrderProcessor {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderEtaService orderEtaService;

    @Scheduled(fixedDelayString = "${app.scheduled-orders.dispatch-interval-ms:60000}")
    @SchedulerLock(name = "scheduled-order-dispatch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void dispatchDueOrders() {
        int totalProcessed = 0;
        while (true) {
            int batchProcessed = dispatchNextBatch();
            if (batchProcessed == 0) break;
            totalProcessed += batchProcessed;
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            if (batchProcessed < 100) break;
        }
        if (totalProcessed > 0) {
            log.info("Scheduled order dispatch completed | processed={}", totalProcessed);
        }
    }

    @Transactional
    protected int dispatchNextBatch() {
        List<Order> due = orderRepository.findByStatusAndScheduledAtLessThanEqual(
                Order.STATUS_SCHEDULED, LocalDateTime.now(), PageRequest.of(0, 100));
        for (Order order : due) {
            try {
                String previous = order.getStatus();
                order.setStatus(Order.STATUS_PLACED);
                order.setScheduledAt(null);
                orderRepository.save(order);
                orderEventPublisher.orderStatusChanged(order.getId(), Order.STATUS_PLACED);
                log.info("Scheduled order dispatched | orderId={} | orderNumber={}",
                        order.getId(), order.getOrderNumber());
            } catch (Exception ex) {
                log.error("Failed to dispatch scheduled order {}: {}", order.getId(), ex.getMessage(), ex);
            }
        }
        return due.size();
    }
}
