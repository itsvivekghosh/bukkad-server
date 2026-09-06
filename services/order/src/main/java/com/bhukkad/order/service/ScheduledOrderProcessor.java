package com.bhukkad.order.service;

import com.bhukkad.common.util.Constants;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.service.OrderEventPublisher;
import com.bhukkad.order.domain.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p>Audit batch A: each order transition runs in its OWN transaction via
 * {@link TransactionTemplate}. The previous code self-invoked a
 * {@code @Transactional} batch method from the scheduler, which bypasses the
 * proxy entirely (no transaction at all) and, when it did apply, wrapped the
 * whole batch so one poisoned order rolled the batch back with it.</p>
 */
@Slf4j
@Component
public class ScheduledOrderProcessor {

    static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderEtaService orderEtaService;
    private final TransactionTemplate transactionTemplate;

    public ScheduledOrderProcessor(OrderRepository orderRepository,
                                   OrderEventPublisher orderEventPublisher,
                                   OrderEtaService orderEtaService,
                                   PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.orderEtaService = orderEtaService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

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

    /**
     * Reads one due batch and dispatches every order in a separate transaction
     * (programmatic boundary — no {@code @Transactional} self-invocation).
     */
    int dispatchNextBatch() {
        List<Order> due = orderRepository.findByStatusAndScheduledAtLessThanEqual(
                Order.STATUS_SCHEDULED, LocalDateTime.now(), PageRequest.of(0, BATCH_SIZE));
        for (Order candidate : due) {
            final Long orderId = candidate.getId();
            try {
                transactionTemplate.executeWithoutResult(status -> dispatchOne(orderId));
            } catch (Exception ex) {
                log.error("Failed to dispatch scheduled order {}: {}", orderId, ex.getMessage(), ex);
            }
        }
        return due.size();
    }

    /** Runs inside its own transaction: re-checks the status guard, then flips SCHEDULED -> PLACED. */
    private void dispatchOne(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElse(null);
        if (order == null || !Order.STATUS_SCHEDULED.equals(order.getStatus())) {
            return; // already moved on (another replica / cancel) — idempotent no-op
        }
        String previous = order.getStatus();
        order.setStatus(Order.STATUS_PLACED);
        order.setScheduledAt(null);
        orderRepository.save(order);
        orderEventPublisher.orderStatusChanged(order.getId(), Order.STATUS_PLACED);
        log.info("Scheduled order dispatched | orderId={} | orderNumber={} | from={}",
                order.getId(), order.getOrderNumber(), previous);
    }
}
