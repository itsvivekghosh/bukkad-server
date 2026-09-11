package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.client.dto.StockReservationLine;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderItemRepository;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.domain.OrderTimelineEvent;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Compensation step of the asynchronous order saga (feature #3).
 *
 * <p>Runs on the {@code payment_failed} consumer side (and on the stuck-order
 * sweep's timeout path) and drives the saga in reverse WITHOUT inline broker
 * or HTTP I/O (G-1): the stock unwind is a reverse outbox event
 * ({@code stock_release_requested}) committed in the same transaction as the
 * {@code PAYMENT_FAILED} status flip, so a crash between the two leaves both
 * or neither — the sweep/replay repairs the rest.</p>
 *
 * <p>Idempotency: a retry after a crash re-reads the order; the status guard
 * ({@code AWAITING_PAYMENT → PAYMENT_FAILED}) makes the second run a no-op,
 * so a re-delivered {@code payment_failed} never releases stock twice.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSagaCompensationService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderTimelineEventRepository timelineRepository;
    private final OrderEventPublisher eventPublisher;

    /**
     * Marks the order {@code PAYMENT_FAILED} and enqueues the reverse
     * {@code stock_release_requested} event atomically. Called from the
     * {@code payment_failed} consumer with {@code reason} from the payment
     * payload, and from the stuck-order sweep with a timeout reason.
     */
    @Transactional
    public void compensatePaymentFailed(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!Order.STATUS_AWAITING_PAYMENT.equals(order.getStatus())) {
            // Idempotent no-op: already compensated/confirmed by a re-delivery.
            log.info("ORDER_SAGA_COMPENSATE_SKIP | orderId={} | status={}", orderId, order.getStatus());
            return;
        }
        order.setStatus(Order.STATUS_PAYMENT_FAILED);
        order.setCancellationReason(truncate(reason));
        orderRepository.save(order);
        eventPublisher.stockReleaseRequested(orderId, reservedLines(orderId));
        eventPublisher.orderStatusChanged(orderId, Order.STATUS_PAYMENT_FAILED);
        log.info("ORDER_SAGA_COMPENSATED | orderId={} | reason={}", orderId, reason);
    }

    /**
     * Confirms the order on {@code payment_settled} and emits the dispatch
     * signal (an {@code OrderStatusChanged → CONFIRMED} outbox event, which
     * delivery/notification already consume). Records the CONFIRMED timeline
     * entry so the async confirmation is observable like the synchronous saga
     * path. Idempotent via the status guard.
     */
    @Transactional
    public void confirmOnPaymentSettled(Long orderId, Long paymentId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (Order.STATUS_CONFIRMED.equals(order.getStatus())) {
            log.info("ORDER_SAGA_CONFIRM_SKIP | orderId={} | already confirmed", orderId);
            return;
        }
        if (!Order.STATUS_AWAITING_PAYMENT.equals(order.getStatus())) {
            throw new BusinessException(
                    "Order %d cannot be confirmed from status %s".formatted(orderId, order.getStatus()));
        }
        order.setStatus(Order.STATUS_CONFIRMED);
        orderRepository.save(order);
        OrderTimelineEvent timeline = new OrderTimelineEvent();
        timeline.setOrderId(orderId);
        timeline.setEventType(Order.STATUS_CONFIRMED);
        timelineRepository.save(timeline);
        eventPublisher.orderStatusChanged(orderId, Order.STATUS_CONFIRMED);
        log.info("ORDER_SAGA_CONFIRMED | orderId={} | paymentId={}", orderId, paymentId);
    }

    /**
     * Stock lines this saga reserved, reconstructed from the order items. The
     * restaurant service's conditional release treats an unknown reservation
     * as a no-op, so a replay after a partial release stays safe.
     */
    private List<StockReservationLine> reservedLines(Long orderId) {
        return orderItemRepository.findByOrderId(orderId).stream()
                .map(item -> StockReservationLine.of(
                        item.getMenuItemId(), item.getItemName(), item.getQuantity()))
                .toList();
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "payment_failed";
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
