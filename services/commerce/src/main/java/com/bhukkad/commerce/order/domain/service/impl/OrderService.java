package com.bhukkad.commerce.order.domain.service.impl;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.common.security.authz.AuthorizationDecision;
import com.bhukkad.common.security.authz.AuthorizationRequest;
import com.bhukkad.common.security.authz.AuthorizationService;
import com.bhukkad.common.security.authz.ResourceOwnershipPolicy;
import com.bhukkad.commerce.order.domain.authz.OrderOwnershipPolicy;
import com.bhukkad.commerce.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.commerce.order.api.dto.response.OrderDetailsResponse;
import com.bhukkad.commerce.order.api.dto.response.OrderResponse;
import com.bhukkad.commerce.order.domain.entity.Order;
import com.bhukkad.commerce.order.domain.service.impl.OrderCreationService;
import com.bhukkad.commerce.order.domain.service.impl.OrderLifecycleService;
import com.bhukkad.commerce.order.domain.service.impl.OrderQueryService;
import com.bhukkad.commerce.order.domain.service.impl.OrderReorderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderCreationService orderCreationService;
    private final OrderQueryService orderQueryService;
    private final OrderLifecycleService orderLifecycleService;
    private final OrderReorderService orderReorderService;
    private final AuthorizationService authorizationService;

    public OrderResponse createOrder(CreateOrderRequest request) {
        return orderCreationService.createOrder(request);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        return orderQueryService.getOrder(orderId);
    }

    /**
     * Public order-read path with resource-level authorization. The order is
     * loaded to obtain the server-derived owner id, then the platform ABAC
     * engine decides owner-or-admin access. Unknown policies fail closed.
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId, TokenPrincipal principal) {
        OrderResponse order = orderQueryService.getOrder(orderId);
        authorizeRead(orderId, order.customerId(), principal);
        return order;
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersForCustomer(Long customerId, Pageable pageable) {
        return orderQueryService.getOrdersForCustomer(customerId, pageable);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersForCustomer(Long customerId) {
        return orderQueryService.getOrdersForCustomer(customerId);
    }

    @Transactional(readOnly = true)
    public List<Order> findByCustomerIdCursor(Long customerId,
                                                      LocalDateTime cursorCreatedAt,
                                                      Long cursorId,
                                                      int pageSize) {
        return orderQueryService.findByCustomerIdCursor(customerId, cursorCreatedAt, cursorId, pageSize);
    }

    @Transactional(readOnly = true)
    public OrderDetailsResponse getOrderDetails(Long orderId) {
        return orderQueryService.getOrderDetails(orderId);
    }

    /** Resource-authorized order-details read for customer principals. */
    @Transactional(readOnly = true)
    public OrderDetailsResponse getOrderDetails(Long orderId, TokenPrincipal principal) {
        OrderDetailsResponse details = orderQueryService.getOrderDetails(orderId);
        authorizeRead(orderId, details.customerId(), principal);
        return details;
    }

    @Transactional(readOnly = true)
    public List<String> timelineEvents(Long orderId) {
        return orderQueryService.timelineEvents(orderId);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersPaged(Pageable pageable) {
        return orderQueryService.getOrdersPaged(pageable);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> toResponseCompatBatch(List<Order> orders) {
        return orderQueryService.toResponseCompatBatch(orders);
    }

    @Transactional(readOnly = true)
    public OrderResponse toResponseCompat(Order order) {
        return orderQueryService.toResponseCompatBatch(List.of(order)).stream().findFirst().orElse(null);
    }

    @Transactional
    public void cancelOrder(Long orderId) {
        orderLifecycleService.cancelOrder(orderId);
    }

    @Transactional
    public OrderResponse transition(Long orderId, String newStatus, String timelineEvent) {
        return orderLifecycleService.transition(orderId, newStatus, timelineEvent);
    }

    @Transactional
    public OrderResponse assignDeliveryAgent(Long orderId, Long agentId) {
        return orderLifecycleService.assignDeliveryAgent(orderId, agentId);
    }

    @Transactional
    public OrderResponse reorder(Long customerId, Long orderId) {
        return orderReorderService.reorder(customerId, orderId);
    }

    private void authorizeRead(Long orderId, Long ownerId, TokenPrincipal principal) {
        AuthorizationRequest request = AuthorizationRequest.fromPrincipal(
                principal,
                "read",
                "order",
                String.valueOf(orderId),
                Map.of(ResourceOwnershipPolicy.OWNER_ID_ATTRIBUTE, ownerId));
        AuthorizationDecision decision = authorizationService.check(
                OrderOwnershipPolicy.POLICY_ID,
                request);
        if (!decision.allowed()) {
            throw new AccessDeniedException("Cannot read another customer's order");
        }
    }
}