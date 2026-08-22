package com.bhukkad.serviceImpl;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.delivery.DeliveryProofService;
import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.delivery.RiderDispatchService;
import com.bhukkad.delivery.RiderEarningService;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.User;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.invoice.OrderInvoiceService;
import com.bhukkad.mapper.OrderMapper;
import com.bhukkad.metrics.BusinessMetrics;
import com.bhukkad.metrics.OrderMetrics;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.PaymentService;
import com.bhukkad.settlement.RestaurantSettlementService;
import com.bhukkad.timeline.OrderTimelineService;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Handles order lifecycle transitions: cancellation, restaurant acceptance,
 * kitchen readiness, delivery-agent assignment, delivery status updates and
 * final delivery completion. Extracted from {@code OrderServiceImpl}.
 *
 * <p>Each transition records a timeline event, publishes the status change and
 * invalidates caches, and several transitions also fire money-path side effects
 * (loyalty points, rider earnings, restaurant settlement, invoice generation).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStatusService {

    private static final Set<Order.OrderStatus> NON_CANCELLABLE_STATUSES = EnumSet.of(
            Order.OrderStatus.OUT_FOR_DELIVERY,
            Order.OrderStatus.DELIVERED,
            Order.OrderStatus.CANCELLED,
            Order.OrderStatus.REFUNDED
    );

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final SecurityUtils securityUtils;
    private final OrderCacheService orderCacheService;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderMapper orderMapper;
    private final OrderMetrics orderMetrics;
    private final BusinessMetrics businessMetrics;
    private final PaymentService paymentService;
    private final RiderDispatchService riderDispatchService;
    private final RiderEarningService riderEarningService;
    private final OrderEtaService orderEtaService;
    private final DeliveryProofService deliveryProofService;
    private final RestaurantSettlementService restaurantSettlementService;
    private final OrderTimelineService orderTimelineService;
    private final OrderInvoiceService orderInvoiceService;

    /** Customer cancels a scheduled order; refunds a completed payment if any. */
    @Transactional
    public OrderResponse cancelScheduledOrder(Long orderId, String reason) {
        Order order = findOrderOrThrow(orderId);
        verifyCustomerOwnsOrder(order);

        if (order.getStatus() != Order.OrderStatus.SCHEDULED) {
            throw new BusinessException("Only scheduled orders can be cancelled via this endpoint");
        }

        order.setCancellationReason(reason);
        order.setCancelledBy(User.UserRole.CUSTOMER.name());

        OrderResponse response = saveWithStatusChange(order, Order.OrderStatus.CANCELLED);

        Customer customer = order.getCustomer();
        if (order.getLoyaltyPointsRedeemed() != null && order.getLoyaltyPointsRedeemed() > 0) {
            customer.setLoyaltyPoints(customer.getLoyaltyPoints() + order.getLoyaltyPointsRedeemed());
            customerRepository.save(customer);
        }

        Payment payment = order.getPayment();
        if (payment != null && payment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            try {
                paymentService.refundPayment(payment.getId());
            } catch (Exception ex) {
                log.warn("Failed to refund payment for cancelled order | orderId={} | paymentId={}",
                        order.getId(), payment.getId(), ex);
                // Continue with cancellation even if refund fails
            }
        }

        orderMetrics.orderCancelled();
        log.info("Scheduled order cancelled | orderId={} | reason={}", orderId, reason);
        return response;
    }

    /** Customer cancels a placed order; rejects statuses that can no longer be cancelled. */
    @Transactional
    public OrderResponse cancelOrder(Long orderId, String reason) {
        Order order = findOrderOrThrow(orderId);
        verifyCustomerOwnsOrder(order);

        if (NON_CANCELLABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException("Order cannot be cancelled in its current status");
        }

        order.setCancellationReason(reason);
        order.setCancelledBy(User.UserRole.CUSTOMER.name());

        OrderResponse response = saveWithStatusChange(order, Order.OrderStatus.CANCELLED);

        Customer customer = order.getCustomer();
        if (order.getLoyaltyPointsRedeemed() != null && order.getLoyaltyPointsRedeemed() > 0) {
            customer.setLoyaltyPoints(customer.getLoyaltyPoints() + order.getLoyaltyPointsRedeemed());
            customerRepository.save(customer);
        }

        Payment payment = order.getPayment();
        if (payment != null && payment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            paymentService.refundPayment(payment.getId());
        }

        orderMetrics.orderCancelled();
        log.info("Order cancelled | orderId={} | reason={}", orderId, reason);
        return response;
    }

    /** Restaurant updates the order status (e.g. PREPARING, READY_FOR_PICKUP). */
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, Order.OrderStatus status) {
        Order order = findOrderOrThrow(orderId);
        verifyRestaurantOwnsOrder(order);
        return saveWithStatusChange(order, status);
    }

    /** Restaurant confirms the order; synonym for {@link #acceptOrder(Long)}. */
    @Transactional
    public OrderResponse confirmOrder(Long orderId) {
        return acceptOrder(orderId);
    }

    /** Restaurant accepts the order and moves it to CONFIRMED. */
    @Transactional
    public OrderResponse acceptOrder(Long orderId) {
        Order order = findOrderOrThrow(orderId);
        verifyRestaurantOwnsOrder(order);
        return saveWithStatusChange(order, Order.OrderStatus.CONFIRMED);
    }

    /** Restaurant marks the order ready for pickup; auto-assigns the nearest rider if possible. */
    @Transactional
    public OrderResponse markOrderReady(Long orderId) {
        Order order = findOrderOrThrow(orderId);
        verifyRestaurantOwnsOrder(order);
        OrderResponse response = saveWithStatusChange(order, Order.OrderStatus.READY_FOR_PICKUP);
        order = findOrderOrThrow(orderId);
        return riderDispatchService.autoAssignNearestRider(order)
                .map(orderMapper::toResponse)
                .orElse(response);
    }

    /** Restaurant assigns a specific delivery agent to the order. */
    @Transactional
    public OrderResponse assignDeliveryAgent(Long orderId, Long agentId) {
        Order order = findOrderOrThrow(orderId);
        verifyRestaurantOwnsOrder(order);

        DeliveryAgent agent = deliveryAgentRepository.findById(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery agent not found"));

        order.setDeliveryAgent(agent);
        order = saveOrder(order);
        invalidateOrderCaches(order);
        orderEventPublisher.publishAgentAssigned(order);
        log.info("Delivery agent assigned | orderId={} | agentId={} | restaurantId={}",
                order.getId(), agentId, order.getRestaurant().getId());
        return orderMapper.toResponse(order);
    }

    /** Delivery agent updates the delivery status; only OUT_FOR_DELIVERY is allowed here. */
    @Transactional
    public OrderResponse updateDeliveryStatus(Long orderId, Order.OrderStatus status) {
        Order order = findOrderOrThrow(orderId);
        verifyDeliveryAgentOwnsOrder(order);

        if (status != Order.OrderStatus.OUT_FOR_DELIVERY) {
            throw new BusinessException("Delivery agents can only mark orders as out for delivery");
        }

        return saveWithStatusChange(order, status);
    }

    /**
     * Marks the order DELIVERED and fires all delivery-completion side effects:
     * loyalty points, rider earnings, restaurant settlement, invoice generation.
     */
    @Transactional
    public OrderResponse markOrderDelivered(Long orderId) {
        Order order = findOrderOrThrow(orderId);
        verifyDeliveryAgentOwnsOrder(order);
        // Gate before any mutation: once the status flips to DELIVERED the loyalty points, rider
        // earning and restaurant settlement all fire, and none of those are cheap to unwind. The
        // check is a no-op unless app.delivery.proof.enforced is true.
        deliveryProofService.assertProofSatisfied(order);

        Order.OrderStatus previousStatus = order.getStatus();
        order.setStatus(Order.OrderStatus.DELIVERED);
        order.setDeliveredAt(LocalDateTime.now());

        Customer customer = order.getCustomer();
        int earnedPoints = PriceCalculator.calculateLoyaltyPoints(order.getTotalAmount());
        customer.setLoyaltyPoints(customer.getLoyaltyPoints() + earnedPoints);
        customerRepository.save(customer);

        if (order.getDeliveryAgent() != null) {
            DeliveryAgent agent = order.getDeliveryAgent();
            agent.setTotalDeliveries(agent.getTotalDeliveries() + 1);
            deliveryAgentRepository.save(agent);
            riderEarningService.recordDeliveryEarning(order, agent);
        }

        restaurantSettlementService.recordSettlementForDeliveredOrder(order);
        orderEtaService.applyLiveEta(order);
        order = saveOrder(order);

        recordTimelineEvent(order.getId(), "ORDER_DELIVERED", Order.OrderStatus.DELIVERED.name(),
                "Order delivered successfully", order.getDeliveryAgent() != null
                        ? order.getDeliveryAgent().getId() : null,
                User.UserRole.DELIVERY_AGENT.name());
        orderInvoiceService.generateOnDelivery(order);

        publishAndInvalidate(order, previousStatus);
        orderMetrics.orderDelivered();
        businessMetrics.delivered();
        log.info("Order delivered | orderId={} | customerId={} | restaurantId={} | agentId={} | total={} | loyaltyPointsEarned={} | deliveredAt={}",
                order.getId(),
                order.getCustomer() != null ? order.getCustomer().getId() : null,
                order.getRestaurant() != null ? order.getRestaurant().getId() : null,
                order.getDeliveryAgent() != null ? order.getDeliveryAgent().getId() : null,
                order.getTotalAmount(),
                earnedPoints,
                order.getDeliveredAt());
        return orderMapper.toResponse(order);
    }

    // ==================== HELPERS ====================

    private Order saveOrder(Order order) {
        try {
            return orderRepository.save(order);
        } catch (OptimisticLockingFailureException ex) {
            throw new BusinessException("Order was updated by another request. Please retry.");
        }
    }

    private OrderResponse saveWithStatusChange(Order order, Order.OrderStatus newStatus) {
        Order.OrderStatus previousStatus = order.getStatus();
        order.setStatus(newStatus);
        orderEtaService.applyLiveEta(order);
        order = saveOrder(order);
        recordTimelineEvent(order.getId(), "STATUS_CHANGE", newStatus.name(),
                "Order status changed from " + previousStatus + " to " + newStatus,
                resolveActorId(), resolveActorRole());
        publishAndInvalidate(order, previousStatus);
        log.info("Order status changed | orderId={} | from={} | to={} | actorId={} | actorRole={}",
                order.getId(), previousStatus, newStatus, resolveActorId(), resolveActorRole());
        return orderMapper.toResponse(order);
    }

    private void recordTimelineEvent(Long orderId, String eventType, String status, String message,
                                     Long actorId, String actorRole) {
        try {
            orderTimelineService.recordEvent(orderId, eventType, status, message, actorId, actorRole);
        } catch (Exception ex) {
            log.warn("Failed to record order timeline event | orderId={} | type={}", orderId, eventType, ex);
        }
    }

    private Long resolveActorId() {
        try {
            return securityUtils.getCurrentUserId();
        } catch (Exception ex) {
            return null;
        }
    }

    private String resolveActorRole() {
        try {
            return securityUtils.getCurrentUser().getRole().name();
        } catch (Exception ex) {
            return null;
        }
    }

    private void publishAndInvalidate(Order order, Order.OrderStatus previousStatus) {
        orderEventPublisher.publishStatusChange(order, previousStatus);
        invalidateOrderCaches(order);
    }

    private void invalidateOrderCaches(Order order) {
        orderCacheService.invalidateOrder(
                order.getId(),
                order.getCustomer().getId(),
                order.getRestaurant().getId());
    }

    private Order findOrderOrThrow(Long orderId) {
        return orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    private void verifyCustomerOwnsOrder(Order order) {
        if (order.getCustomer() == null || !order.getCustomer().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("You can only access your own orders");
        }
    }

    private void verifyRestaurantOwnsOrder(Order order) {
        if (!order.getRestaurant().getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Not your restaurant's order");
        }
    }

    private void verifyDeliveryAgentOwnsOrder(Order order) {
        Long currentAgentId = resolveCurrentDeliveryAgentId();
        if (order.getDeliveryAgent() == null
                || !order.getDeliveryAgent().getId().equals(currentAgentId)) {
            throw new UnauthorizedException("Order is not assigned to you");
        }
    }

    private Long resolveCurrentDeliveryAgentId() {
        User user = securityUtils.getCurrentUser();
        if (user.getRole() != User.UserRole.DELIVERY_AGENT) {
            throw new UnauthorizedException("Not a delivery agent account");
        }
        return user.getId();
    }
}
