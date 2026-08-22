package com.bhukkad.serviceImpl;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.datasource.UseReadReplica;
import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.dto.request.BatchOrderRequest;
import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.BatchOrderResponse;
import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.OrderSummaryResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.User;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.mapper.OrderMapper;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.OrderService;
import com.bhukkad.util.CursorUtils;
import com.bhukkad.util.PaginationUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Order service facade. Read/query operations live here; order creation is
 * delegated to {@link OrderPlacementService} and lifecycle status transitions
 * to {@link OrderStatusService}. The public {@link OrderService} contract is
 * unchanged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private static final List<Order.OrderStatus> KITCHEN_ACTIVE_STATUSES = List.of(
            Order.OrderStatus.PLACED,
            Order.OrderStatus.CONFIRMED,
            Order.OrderStatus.PREPARING,
            Order.OrderStatus.READY_FOR_PICKUP
    );

    private final OrderRepository orderRepository;
    private final RestaurantRepository restaurantRepository;
    private final SecurityUtils securityUtils;
    private final OrderCacheService orderCacheService;
    private final RedisCacheService redisCacheService;
    private final OrderMapper orderMapper;
    private final OrderEtaService orderEtaService;
    private final OrderPlacementService orderPlacementService;
    private final OrderStatusService orderStatusService;

    @Value("${cache.ttl.kitchen-queue:15}")
    private long kitchenQueueTtlSeconds;

    // ==================== ORDER CREATION ====================

    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest request, String idempotencyKey) {
        return orderPlacementService.createOrder(request, idempotencyKey);
    }

    @Override
    @Transactional
    public BatchOrderResponse createBatchOrders(BatchOrderRequest request, String idempotencyKey) {
        return orderPlacementService.createBatchOrders(request, idempotencyKey);
    }

    // ==================== ORDER QUERIES ====================

    @Override
    @UseReadReplica
    public OrderResponse getOrderById(Long id) {
        Order order = findOrderOrThrow(id);
        verifyCustomerOwnsOrder(order);
        return orderMapper.toResponse(order);
    }

    @Override
    @UseReadReplica
    public OrderResponse getOrderByNumber(String orderNumber) {
        Order order = orderRepository.findByOrderNumberWithDetails(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        verifyCustomerOwnsOrder(order);
        return orderMapper.toResponse(order);
    }

    /**
     * Batch read — returns a map keyed by order id, restricted to orders
     * owned by the caller. Ids that the caller does not own, or that do not
     * exist, are silently dropped from the result map rather than throwing —
     * this matches the behaviour of {@code POST /_mget} style APIs in the
     * wider industry and lets the caller treat a missing entry as "you can't
     * see this", which is also the correct auth response.
     *
     * <p>Cap is enforced upstream in the controller to avoid surprising
     * callers with a 50-row response when they asked for 100.
     */
    @Override
    @UseReadReplica
    public java.util.Map<Long, OrderResponse> getOrdersByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        Long callerId = securityUtils.getCurrentUserId();
        java.util.List<Order> orders = orderRepository.findAllById(ids);
        java.util.Map<Long, OrderResponse> result = new java.util.LinkedHashMap<>();
        for (Order order : orders) {
            // Re-check ownership in code (not just SQL) because the customer
            // filter is enforced by the controller layer for the single-order
            // endpoints and we want the same property here.
            if (order.getCustomer() != null && order.getCustomer().getId().equals(callerId)) {
                result.put(order.getId(), orderMapper.toResponse(order));
            }
        }
        return result;
    }

    @Override
    @UseReadReplica
    public PagedResponse<OrderSummaryResponse> getCustomerOrders(int page, int size) {
        Long customerId = securityUtils.getCurrentUserId();
        Page<OrderSummaryResponse> orders = orderRepository.findCustomerOrderSummaries(
                customerId,
                PaginationUtils.page(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(orders);
    }

    @Override
    @UseReadReplica
    public PagedResponse<OrderSummaryResponse> getCustomerScheduledOrders(int page, int size) {
        Long customerId = securityUtils.getCurrentUserId();
        Page<OrderSummaryResponse> orders = orderRepository.findCustomerScheduledOrderSummaries(
                customerId,
                PaginationUtils.page(page, size, Sort.by(Sort.Direction.ASC, "scheduledAt")));
        return PagedResponse.from(orders);
    }

    @Override
    @UseReadReplica
    public CursorPagedResponse<OrderSummaryResponse> getCustomerScheduledOrdersByCursor(String cursor, int size) {
        Long customerId = securityUtils.getCurrentUserId();
        CursorUtils.OrderCursor orderCursor = CursorUtils.decode(cursor).orElse(null);
        int safeSize = Math.min(Math.max(size, 1), PaginationUtils.MAX_PAGE_SIZE);
        List<OrderSummaryResponse> batch = orderRepository.findCustomerScheduledOrderSummariesAfterCursor(
                customerId,
                orderCursor != null ? orderCursor.createdAt() : null,
                orderCursor != null ? orderCursor.id() : null,
                PageRequest.of(0, safeSize + 1));
        return toCursorPage(batch, safeSize);
    }

    @Override
    @UseReadReplica
    public CursorPagedResponse<OrderSummaryResponse> getCustomerOrdersByCursor(String cursor, int size) {
        Long customerId = securityUtils.getCurrentUserId();
        CursorUtils.OrderCursor orderCursor = CursorUtils.decode(cursor).orElse(null);
        int safeSize = Math.min(Math.max(size, 1), PaginationUtils.MAX_PAGE_SIZE);
        List<OrderSummaryResponse> batch = orderRepository.findCustomerOrderSummariesAfterCursor(
                customerId,
                orderCursor != null ? orderCursor.createdAt() : null,
                orderCursor != null ? orderCursor.id() : null,
                PageRequest.of(0, safeSize + 1));
        return toCursorPage(batch, safeSize);
    }

    @Override
    @UseReadReplica
    public PagedResponse<OrderSummaryResponse> getRestaurantOrders(Long restaurantId, int page, int size) {
        verifyRestaurantOwnership(restaurantId);
        Page<OrderSummaryResponse> orders = orderRepository.findRestaurantOrderSummaries(
                restaurantId,
                PaginationUtils.page(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(orders);
    }

    @Override
    @UseReadReplica
    public CursorPagedResponse<OrderSummaryResponse> getRestaurantOrdersByCursor(
            Long restaurantId, String cursor, int size) {
        verifyRestaurantOwnership(restaurantId);
        CursorUtils.OrderCursor orderCursor = CursorUtils.decode(cursor).orElse(null);
        int safeSize = Math.min(Math.max(size, 1), PaginationUtils.MAX_PAGE_SIZE);
        List<OrderSummaryResponse> batch = orderRepository.findRestaurantOrderSummariesAfterCursor(
                restaurantId,
                orderCursor != null ? orderCursor.createdAt() : null,
                orderCursor != null ? orderCursor.id() : null,
                PageRequest.of(0, safeSize + 1));
        return toCursorPage(batch, safeSize);
    }

    @Override
    @UseReadReplica
    public PagedResponse<OrderSummaryResponse> getDeliveryAgentOrders(Long agentId, int page, int size) {
        Long currentAgentId = resolveCurrentDeliveryAgentId();
        Long deliveryAgentId = agentId != null ? agentId : currentAgentId;
        if (!deliveryAgentId.equals(currentAgentId)) {
            throw new UnauthorizedException("Cannot access another agent's deliveries");
        }
        Page<OrderSummaryResponse> orders = orderRepository.findDeliveryAgentOrderSummaries(
                deliveryAgentId,
                PaginationUtils.page(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(orders);
    }

    @Override
    @UseReadReplica
    public CursorPagedResponse<OrderSummaryResponse> getDeliveryAgentOrdersByCursor(
            Long agentId, String cursor, int size) {
        Long currentAgentId = resolveCurrentDeliveryAgentId();
        Long deliveryAgentId = agentId != null ? agentId : currentAgentId;
        if (!deliveryAgentId.equals(currentAgentId)) {
            throw new UnauthorizedException("Cannot access another agent's deliveries");
        }
        CursorUtils.OrderCursor orderCursor = CursorUtils.decode(cursor).orElse(null);
        int safeSize = Math.min(Math.max(size, 1), PaginationUtils.MAX_PAGE_SIZE);
        List<OrderSummaryResponse> batch = orderRepository.findDeliveryAgentOrderSummariesAfterCursor(
                deliveryAgentId,
                orderCursor != null ? orderCursor.createdAt() : null,
                orderCursor != null ? orderCursor.id() : null,
                PageRequest.of(0, safeSize + 1));
        return toCursorPage(batch, safeSize);
    }

    @Override
    @UseReadReplica
    public List<OrderSummaryResponse> getPendingOrdersForRestaurant(Long restaurantId, int limit) {
        verifyRestaurantOwnership(restaurantId);
        return orderRepository.findPendingSummariesForRestaurant(
                restaurantId,
                Order.OrderStatus.PLACED,
                PaginationUtils.limited(limit, Sort.by(Sort.Direction.ASC, "createdAt")));
    }

    @Override
    @UseReadReplica
    public List<OrderSummaryResponse> getKitchenActiveOrders(Long restaurantId, int limit) {
        verifyRestaurantOwnership(restaurantId);
        String cacheKey = CacheKeyGenerator.kitchenQueue(restaurantId);
        var cached = redisCacheService.getList(cacheKey, OrderSummaryResponse.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        List<OrderSummaryResponse> queue = orderRepository.findKitchenActiveSummaries(
                restaurantId,
                KITCHEN_ACTIVE_STATUSES,
                PaginationUtils.limited(limit, Sort.by(Sort.Direction.ASC, "createdAt")));

        redisCacheService.set(cacheKey, queue, kitchenQueueTtlSeconds);
        return queue;
    }

    // ==================== STATUS TRANSITIONS (delegated) ====================

    @Override
    @Transactional
    public OrderResponse cancelScheduledOrder(Long orderId, String reason) {
        return orderStatusService.cancelScheduledOrder(orderId, reason);
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, Order.OrderStatus status) {
        return orderStatusService.updateOrderStatus(orderId, status);
    }

    @Override
    @Transactional
    public OrderResponse confirmOrder(Long orderId) {
        return orderStatusService.confirmOrder(orderId);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long orderId, String reason) {
        return orderStatusService.cancelOrder(orderId, reason);
    }

    @Override
    @Transactional
    public OrderResponse acceptOrder(Long orderId) {
        return orderStatusService.acceptOrder(orderId);
    }

    @Override
    @Transactional
    public OrderResponse markOrderReady(Long orderId) {
        return orderStatusService.markOrderReady(orderId);
    }

    @Override
    @Transactional
    public OrderResponse assignDeliveryAgent(Long orderId, Long agentId) {
        return orderStatusService.assignDeliveryAgent(orderId, agentId);
    }

    @Override
    @Transactional
    public OrderResponse updateDeliveryStatus(Long orderId, Order.OrderStatus status) {
        return orderStatusService.updateDeliveryStatus(orderId, status);
    }

    @Override
    @Transactional
    public OrderResponse markOrderDelivered(Long orderId) {
        return orderStatusService.markOrderDelivered(orderId);
    }

    // ==================== TRACKING ====================

    /**
     * Returns a live tracking snapshot of an order for the authenticated customer.
     *
     * <p>Access is restricted to the order's owner. Both the cache read and the
     * database read are customer-scoped: the cache key embeds the caller's id so a
     * snapshot rendered for the owner can never be served to another customer, and
     * on a cache miss {@link #verifyCustomerOwnsOrder(Order)} rejects non-owners with
     * an {@code UnauthorizedException}. The order is loaded with
     * {@link #findOrderOrThrow(Long)} because the ownership check dereferences the
     * LAZY customer association and {@code spring.jpa.open-in-view} is disabled.
     *
     * @param orderId the order to track
     * @return the tracking snapshot including live ETA
     */
    @Override
    @Transactional(readOnly = false)
    public OrderResponse trackOrder(Long orderId) {
        Long customerId = securityUtils.getCurrentUserId();

        var cached = orderCacheService.getTrackedOrder(orderId, customerId);
        if (cached.isPresent()) {
            return cached.get();
        }

        Order order = findOrderOrThrow(orderId);
        verifyCustomerOwnsOrder(order);
        orderEtaService.applyLiveEta(order);
        OrderResponse response = orderMapper.toResponse(order);
        orderCacheService.cacheTrackedOrder(orderId, customerId, response);
        return response;
    }

    // ==================== HELPERS ====================

    private CursorPagedResponse<OrderSummaryResponse> toCursorPage(
            List<OrderSummaryResponse> batch, int size) {
        boolean hasNext = batch.size() > size;
        List<OrderSummaryResponse> items = hasNext ? batch.subList(0, size) : batch;
        String nextCursor = null;
        if (hasNext && !items.isEmpty()) {
            OrderSummaryResponse last = items.get(items.size() - 1);
            nextCursor = CursorUtils.encode(last.getCreatedAt(), last.getId());
        }
        return CursorPagedResponse.of(items, nextCursor, hasNext);
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

    private void verifyRestaurantOwnership(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findByIdWithDetails(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        if (!restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Not your restaurant's order");
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
