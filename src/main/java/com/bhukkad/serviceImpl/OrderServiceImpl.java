package com.bhukkad.serviceImpl;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.datasource.UseReadReplica;
import com.bhukkad.delivery.RiderDispatchService;
import com.bhukkad.delivery.RiderEarningService;
import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.OrderItemResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.OrderSummaryResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Cart;
import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderItem;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.User;
import com.bhukkad.idempotency.OrderIdempotencyService;
import com.bhukkad.mapper.OrderMapper;
import com.bhukkad.service.DeliveryService;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.AddressRepository;
import com.bhukkad.repository.CartItemRepository;
import com.bhukkad.repository.CartRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CouponService;
import com.bhukkad.service.OrderPricingService;
import com.bhukkad.service.OrderService;
import com.bhukkad.service.PaymentService;
import com.bhukkad.util.Constants;
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

import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.wallet.WalletService;
import com.bhukkad.util.PriceCalculator;
import com.bhukkad.metrics.OrderMetrics;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private static final Set<Order.OrderStatus> NON_CANCELLABLE_STATUSES = EnumSet.of(
            Order.OrderStatus.OUT_FOR_DELIVERY,
            Order.OrderStatus.DELIVERED,
            Order.OrderStatus.CANCELLED,
            Order.OrderStatus.REFUNDED
    );

    private static final List<Order.OrderStatus> KITCHEN_ACTIVE_STATUSES = List.of(
            Order.OrderStatus.PLACED,
            Order.OrderStatus.CONFIRMED,
            Order.OrderStatus.PREPARING,
            Order.OrderStatus.READY_FOR_PICKUP
    );

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantRepository restaurantRepository;
    private final AddressRepository addressRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final SecurityUtils securityUtils;
    private final OrderCacheService orderCacheService;
    private final RedisCacheService redisCacheService;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderPricingService orderPricingService;
    private final CouponService couponService;
    private final PaymentService paymentService;
    private final DeliveryService deliveryService;
    private final RiderDispatchService riderDispatchService;
    private final OrderMapper orderMapper;
    private final OrderIdempotencyService orderIdempotencyService;
    private final OrderMetrics orderMetrics;
    private final WalletService walletService;
    private final RiderEarningService riderEarningService;

    @Value("${cache.ttl.kitchen-queue:15}")
    private long kitchenQueueTtlSeconds;

    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest request, String idempotencyKey) {
        if (StringUtils.hasText(idempotencyKey)) {
            var cached = orderIdempotencyService.findCompletedResponse(idempotencyKey);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        Long customerId = securityUtils.getCurrentUserId();
        if (StringUtils.hasText(idempotencyKey)) {
            orderIdempotencyService.beginOrderCreate(idempotencyKey, customerId);
        }

        try {
            return doCreateOrder(request, idempotencyKey, customerId);
        } catch (RuntimeException ex) {
            orderIdempotencyService.failOrderCreate(idempotencyKey);
            throw ex;
        }
    }

    private OrderResponse doCreateOrder(OrderRequest request, String idempotencyKey, Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        Restaurant restaurant = restaurantRepository.findByIdWithDetails(request.getRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!Boolean.TRUE.equals(restaurant.getIsActive())) {
            throw new BusinessException("Restaurant is not accepting orders");
        }
        if (!Boolean.TRUE.equals(restaurant.getIsOpen())) {
            throw new BusinessException("Restaurant is currently closed");
        }

        Cart cart = cartRepository.findByCustomerIdWithRestaurant(customerId)
                .orElseThrow(() -> new BusinessException("Cart is empty"));

        List<CartItem> cartItems = cartItemRepository.findByCartIdWithMenuItem(cart.getId()).stream()
                .filter(item -> item.getMenuItem().getCategory().getRestaurant().getId().equals(restaurant.getId()))
                .collect(Collectors.toList());
        if (cartItems.isEmpty()) {
            throw new BusinessException("No cart items for the selected restaurant");
        }

        if (cart.getRestaurant() != null && !cart.getRestaurant().getId().equals(restaurant.getId())) {
            // Multi-restaurant cart: restaurant field may point to another group
        }

        orderPricingService.validateCartItems(restaurant, cartItems);

        Address address = addressRepository.findByIdWithCustomer(request.getDeliveryAddressId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (!address.getCustomer().getId().equals(customerId)) {
            throw new BusinessException("Delivery address does not belong to customer");
        }

        OrderPricingService.OrderPricingResult pricing = orderPricingService.calculate(
                restaurant,
                cartItems,
                request.getCouponCode(),
                customer,
                request.getLoyaltyPointsToRedeem(),
                request.getPaymentMethod(),
                request.getWalletAmountToUse(),
                request.getUseWallet());

        Order order = new Order();
        order.setOrderNumber(generateOrderNumber());
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setDeliveryAddress(address);
        order.setStatus(Order.OrderStatus.PLACED);
        order.setSpecialInstructions(request.getSpecialInstructions());
        order.setContactlessDelivery(Boolean.TRUE.equals(request.getContactlessDelivery()));
        order.setSubtotal(pricing.subtotal());
        order.setDeliveryFee(pricing.deliveryFee());
        order.setTaxAmount(pricing.taxAmount());
        order.setDiscountAmount(pricing.discountAmount());
        order.setTotalAmount(pricing.totalAmount());
        order.setLoyaltyPointsRedeemed(pricing.loyaltyPointsRedeemed());
        order.setWalletAmountUsed(pricing.walletAmountUsed());
        order.setAppliedCoupon(pricing.appliedCoupon());

        int deliveryMinutes = restaurant.getAverageDeliveryTime() != null
                ? restaurant.getAverageDeliveryTime()
                : Constants.DEFAULT_DELIVERY_TIME;
        order.setEstimatedDeliveryTime(deliveryMinutes);
        order.setEstimatedDeliveryAt(LocalDateTime.now().plusMinutes(deliveryMinutes));

        order.setOrderItems(buildOrderItems(order, cartItems));

        order = saveOrder(order);

        if (pricing.loyaltyPointsRedeemed() > 0) {
            customer.setLoyaltyPoints(customer.getLoyaltyPoints() - pricing.loyaltyPointsRedeemed());
        }
        if (pricing.walletAmountUsed() > 0) {
            walletService.debit(
                    customer,
                    pricing.walletAmountUsed(),
                    WalletTransaction.TransactionType.ORDER_DEBIT,
                    null,
                    "Order " + order.getOrderNumber());
        } else {
            customerRepository.save(customer);
        }

        if (pricing.appliedCoupon() != null) {
            couponService.recordCouponUsage(pricing.appliedCoupon());
        }

        String paymentMethod = normalizePaymentMethod(request.getPaymentMethod());
        String paymentIdempotencyKey = StringUtils.hasText(idempotencyKey)
                ? "payment:" + idempotencyKey
                : null;
        Payment payment = paymentService.createPayment(order.getId(), paymentMethod, paymentIdempotencyKey);
        if (payment.getPaymentMethod() != Payment.PaymentMethod.CASH_ON_DELIVERY) {
            payment = paymentService.processPayment(payment.getId(), paymentIdempotencyKey);
        }
        order.setPayment(payment);

        clearCartItemsForRestaurant(cart, restaurant.getId());

        orderCacheService.invalidateOrder(order.getId(), customerId, restaurant.getId());
        orderEventPublisher.publishCreated(order);
        orderMetrics.orderCreated();
        log.info("Order created | orderId={} | customerId={} | total={}",
                order.getId(), customerId, order.getTotalAmount());
        OrderResponse response = orderMapper.toResponse(order);
        orderIdempotencyService.completeOrderCreate(idempotencyKey, response);
        return response;
    }

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
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, Order.OrderStatus status) {
        Order order = findOrderOrThrow(orderId);
        verifyRestaurantOwnsOrder(order);
        return saveWithStatusChange(order, status);
    }

    @Override
    @Transactional
    public OrderResponse confirmOrder(Long orderId) {
        return acceptOrder(orderId);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long orderId, String reason) {
        Order order = findOrderOrThrow(orderId);
        verifyCustomerOwnsOrder(order);

        if (NON_CANCELLABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException("Order cannot be cancelled in its current status");
        }

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

    @Override
    @Transactional
    public OrderResponse acceptOrder(Long orderId) {
        Order order = findOrderOrThrow(orderId);
        verifyRestaurantOwnsOrder(order);
        return saveWithStatusChange(order, Order.OrderStatus.CONFIRMED);
    }

    @Override
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

    @Override
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
        return orderMapper.toResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse updateDeliveryStatus(Long orderId, Order.OrderStatus status) {
        Order order = findOrderOrThrow(orderId);
        verifyDeliveryAgentOwnsOrder(order);

        if (status != Order.OrderStatus.OUT_FOR_DELIVERY) {
            throw new BusinessException("Delivery agents can only mark orders as out for delivery");
        }

        return saveWithStatusChange(order, status);
    }

    @Override
    @Transactional
    public OrderResponse markOrderDelivered(Long orderId) {
        Order order = findOrderOrThrow(orderId);
        verifyDeliveryAgentOwnsOrder(order);

        Order.OrderStatus previousStatus = order.getStatus();
        order.setStatus(Order.OrderStatus.DELIVERED);
        order.setDeliveredAt(LocalDateTime.now());
        order = saveOrder(order);

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

        publishAndInvalidate(order, previousStatus);
        orderMetrics.orderDelivered();
        return orderMapper.toResponse(order);
    }

    @Override
    @UseReadReplica
    public OrderResponse trackOrder(Long orderId) {
        var cached = orderCacheService.getTrackedOrder(orderId);
        if (cached.isPresent()) {
            return cached.get();
        }

        OrderResponse response = getOrderById(orderId);
        orderCacheService.cacheTrackedOrder(orderId, response);
        return response;
    }

    // ==================== HELPERS ====================

    private Order saveOrder(Order order) {
        try {
            return orderRepository.save(order);
        } catch (OptimisticLockingFailureException ex) {
            throw new BusinessException("Order was updated by another request. Please retry.");
        }
    }

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

    private OrderResponse saveWithStatusChange(Order order, Order.OrderStatus newStatus) {
        Order.OrderStatus previousStatus = order.getStatus();
        order.setStatus(newStatus);
        order = saveOrder(order);
        publishAndInvalidate(order, previousStatus);
        return orderMapper.toResponse(order);
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
        if (!order.getCustomer().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("You can only access your own orders");
        }
    }

    private void verifyRestaurantOwnership(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findByIdWithDetails(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        verifyRestaurantOwnsOrder(restaurant);
    }

    private void verifyRestaurantOwnsOrder(Restaurant restaurant) {
        if (!restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Not your restaurant's order");
        }
    }

    private void verifyRestaurantOwnsOrder(Order order) {
        verifyRestaurantOwnsOrder(order.getRestaurant());
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

    private String generateOrderNumber() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private List<OrderItem> buildOrderItems(Order order, List<CartItem> cartItems) {
        List<OrderItem> orderItems = new ArrayList<>();
        for (CartItem cartItem : cartItems) {
            MenuItem menuItem = cartItem.getMenuItem();
            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setMenuItem(menuItem);
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setPrice(menuItem.getPrice());
            orderItem.setSpecialInstructions(cartItem.getSpecialInstructions());
            orderItems.add(orderItem);
        }
        return orderItems;
    }

    private void clearCartItemsForRestaurant(Cart cart, Long restaurantId) {
        List<CartItem> items = cartItemRepository.findByCartIdWithMenuItem(cart.getId());
        for (CartItem item : items) {
            if (item.getMenuItem().getCategory().getRestaurant().getId().equals(restaurantId)) {
                cartItemRepository.delete(item);
            }
        }
        List<CartItem> remaining = cartItemRepository.findByCartId(cart.getId());
        if (remaining.isEmpty()) {
            cart.setRestaurant(null);
        } else if (cart.getRestaurant() != null && cart.getRestaurant().getId().equals(restaurantId)) {
            Long nextRestaurantId = remaining.get(0).getMenuItem().getCategory().getRestaurant().getId();
            restaurantRepository.findById(nextRestaurantId).ifPresent(cart::setRestaurant);
        }
        cartRepository.save(cart);
    }

    private void clearCart(Cart cart) {
        cartItemRepository.deleteByCartId(cart.getId());
        cart.setRestaurant(null);
        cartRepository.save(cart);
    }

    private String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new BusinessException("Payment method is required");
        }
        String normalized = paymentMethod.trim().toUpperCase();
        if ("COD".equals(normalized)) {
            return Payment.PaymentMethod.CASH_ON_DELIVERY.name();
        }
        try {
            Payment.PaymentMethod.valueOf(normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Invalid payment method: " + paymentMethod);
        }
    }
}
