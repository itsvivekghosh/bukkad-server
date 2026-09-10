package com.bhukkad.order.service;

import com.bhukkad.order.OrderSagaProperties;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Stuck-order sweeper for the asynchronous order saga (feature #3).
 *
 * <p>An order parked in {@code AWAITING_PAYMENT} past
 * {@code app.order.async-saga.stuck-order-minutes} means the payment verdict
 * never arrived (lost event, crashed consumer, PSP silence). The sweep
 * compensates each stuck order through {@link OrderSagaCompensationService}
 * — status-guarded, so a late {@code payment_settled} for an order already
 * swept is a no-op and a re-delivered {@code payment_settled} for a swept
 * order is rejected by the state guard.</p>
 *
 * <p>ShedLock keeps a single runner across replicas; each order flips inside
 * its own {@link TransactionTemplate} transaction (batch A convention) so one
 * poisoned order never rolls back the batch. After this sweep runs there are
 * no stuck orders left in {@code AWAITING_PAYMENT} older than the threshold —
 * the chaos property the audit requires.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.order.async-saga.enabled", havingValue = "true")
public class StuckOrderSweep {

    static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderSagaCompensationService compensationService;
    private final OrderSagaProperties properties;
    private final TransactionTemplate transactionTemplate;

    public StuckOrderSweep(OrderRepository orderRepository,
                           OrderSagaCompensationService compensationService,
                           OrderSagaProperties properties,
                           PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.compensationService = compensationService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.order.async-saga.sweep-interval-ms:60000}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "async-saga-stuck-order-sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void sweepStuckOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(properties.getStuckOrderMinutes());
        int swept = 0;
        List<Order> stuck = orderRepository.findByStatusAndUpdatedAtLessThanEqual(
                Order.STATUS_AWAITING_PAYMENT, cutoff, PageRequest.of(0, BATCH_SIZE));
        for (Order candidate : stuck) {
            final Long orderId = candidate.getId();
            try {
                Integer compensated = transactionTemplate.execute(status -> {
                    Order fresh = orderRepository.findById(orderId).orElse(null);
                    if (fresh == null
                            || !Order.STATUS_AWAITING_PAYMENT.equals(fresh.getStatus())
                            || fresh.getUpdatedAt().isAfter(cutoff)) {
                        return 0; // raced with the payment verdict — leave it alone
                    }
                    compensationService.compensatePaymentFailed(orderId, "saga_timeout");
                    return 1;
                });
                swept += compensated == null ? 0 : compensated;
            } catch (Exception ex) {
                log.error("STUCK_ORDER_SWEEP_FAILED | orderId={} | error={}", orderId, ex.getMessage(), ex);
            }
        }
        if (swept > 0) {
            log.info("STUCK_ORDER_SWEEP | swept={}", swept);
        }
    }
}
