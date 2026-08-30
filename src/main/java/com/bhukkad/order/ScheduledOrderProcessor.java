package com.bhukkad.order;

import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledOrderProcessor {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderEtaService orderEtaService;

    /**
     * Promotes due {@code SCHEDULED} orders to {@code PLACED}. This is the
     * single poller for scheduled orders: it absorbs the former
     * {@code ScheduledOrderScheduler} so two 60-second sweeps can no longer
     * race on the same order (duplicate {@code ORDER_STATUS_CHANGED} events and
     * divergent side effects — one path applied live ETA, the other cleared
     * {@code scheduledAt}). ShedLock (single-runner across replicas) plus the
     * {@code SCHEDULED -> PLACED} status guard make dispatch idempotent under
     * horizontal scaling; a failure on one order is contained so the rest of the
     * batch still dispatches.
     */
    @Scheduled(fixedDelayString = "${app.scheduled-orders.dispatch-interval-ms:60000}")
    @SchedulerLock(name = "scheduled-order-dispatch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void dispatchDueOrders() {
        int totalProcessed = 0;
        while (true) {
            int batchProcessed = dispatchNextBatch();
            if (batchProcessed == 0) break;
            totalProcessed += batchProcessed;
            // Short pause to yield DB and avoid tight loop at very high due volume
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            // Loop always from page 0 to avoid OFFSET drift — status guard removes dispatched rows
            if (batchProcessed < 100) break;
        }
        if (totalProcessed > 0) {
            log.info("Scheduled order dispatch completed | processed={}", totalProcessed);
        }
    }

    @Transactional
    protected int dispatchNextBatch() {
        Page<Order> due = orderRepository.findByStatusAndScheduledAtLessThanEqual(
                Order.OrderStatus.SCHEDULED, LocalDateTime.now(), PageRequest.of(0, 100));
        for (Order order : due.getContent()) {
            try {
                Order.OrderStatus previous = order.getStatus();
                order.setStatus(Order.OrderStatus.PLACED);
                order.setScheduledAt(null);
                orderEtaService.applyLiveEta(order);
                orderRepository.save(order);
                // Publish after save but within same Tx; listener will see committed state via outbox
                orderEventPublisher.publishStatusChange(order, previous);
                log.info("Scheduled order dispatched | orderId={} | orderNumber={}",
                        order.getId(), order.getOrderNumber());
            } catch (Exception ex) {
                log.error("Failed to dispatch scheduled order {}: {}", order.getId(), ex.getMessage(), ex);
            }
        }
        return due.getNumberOfElements();
    }
}
