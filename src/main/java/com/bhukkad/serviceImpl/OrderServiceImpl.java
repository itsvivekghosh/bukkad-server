package com.bhukkad.serviceImpl;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.datasource.UseReadReplica;
import com.bhukkad.delivery.DeliveryProofService;
import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.delivery.RiderDispatchService;
import com.bhukkad.delivery.RiderEarningService;
import com.bhukkad.dto.request.BatchOrderRequest;
import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.BatchOrderResponse;
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
import com.bhukkad.inventory.StockReservationService;
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
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.invoice.OrderInvoiceService;
import com.bhukkad.restaurant.RestaurantBusyService;
import com.bhukkad.timeline.OrderTimelineService;
import com.bhukkad.order.ScheduledOrderValidator;
import com.bhukkad.settlement.RestaurantSettlementService;
import com.bhukkad.service.CouponService;
import com.bhukkad.service.OrderPricingService;
import com.bhukkad.service.OrderService;
import com.bhukkad.service.PaymentService;
import com.bhukkad.util.Constants;
import com.bhukkad.util.CursorUtils;
import com.bhukkad.util.DateTimeUtils;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final MenuItemRepository menuItemRepository;
    private final ScheduledOrderValidator scheduledOrderValidator;
    private final OrderEtaService orderEtaService;
    private final DeliveryProofService deliveryProofService;
    private final RestaurantSettlementService restaurantSettlementService;
    private final StockReservationService stockReservationService;
    private final OrderTimelineService orderTimelineService;
    private final OrderInvoiceService orderInvoiceService;
    private final RestaurantBusyService restaurantBusyService;

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

    @Override
    @Transactional
    public BatchOrderResponse createBatchOrders(BatchOrderRequest request, String idempotencyKey) {
        Long customerId = securityUtils.getCurrentUserId();
        Cart cart = cartRepository.findByCustomerIdWithRestaurant(customerId)
                .orElseThrow(() -> new BusinessException("Cart is empty"));
        List<CartItem> allItems = cartItemRepository.findByCartIdWithMenuItem(cart.getId());
        if (allItems.isEmpty()) {
            throw new BusinessException("Cart is empty");
        }

        Map<Long, List<CartItem>> byRestaurant = groupCartItemsByRestaurant(allItems);
        double cartSubtotal = allItems.stream()
                .mapToDouble(item -> PriceCalculator.calculateSubtotal(
                        item.getMenuItem().getPrice(), item.getQuantity()))
                .sum();
        double totalTip = request.getTipAmount() != null ? Math.max(0, request.getTipAmount()) : 0.0;

        List<OrderResponse> orders = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (Map.Entry<Long, List<CartItem>> entry : byRestaurant.entrySet()) {
            Long restaurantId = entry.getKey();
            double groupSubtotal = entry.getValue().stream()
                    .mapToDouble(item -> PriceCalculator.calculateSubtotal(
                            item.getMenuItem().getPrice(), item.getQuantity()))
                    .sum();
            double groupTip = cartSubtotal > 0
                    ? PriceCalculator.roundToTwoDecimals(totalTip * (groupSubtotal / cartSubtotal))
                    : 0.0;

            OrderRequest orderRequest = new OrderRequest();
            orderRequest.setRestaurantId(restaurantId);
            orderRequest.setDeliveryAddressId(request.getDeliveryAddressId());
            orderRequest.setSpecialInstructions(request.getSpecialInstructions());
            orderRequest.setContactlessDelivery(request.getContactlessDelivery());
            orderRequest.setPaymentMethod(request.getPaymentMethod());
            orderRequest.setTipAmount(groupTip);

            try {
                OrderResponse response = doCreateOrder(orderRequest, null, customerId);
                orders.add(response);
                successCount++;
            } catch (RuntimeException ex) {
                errors.add("Restaurant " + restaurantId + ": " + ex.getMessage());
                failureCount++;
            }
        }

        return BatchOrderResponse.builder()
                .orders(orders)
                .successCount(successCount)
                .failureCount(failureCount)
                .errors(errors)
                .build();
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
        restaurantBusyService.assertAcceptingOrders(restaurant.getId());
        scheduledOrderValidator.validateScheduledAt(request.getScheduledAt());
        boolean scheduled = scheduledOrderValidator.isScheduledOrder(request.getScheduledAt());
        if (!scheduled && restaurant.getOpeningTime() != null && restaurant.getClosingTime() != null
                && !DateTimeUtils.isRestaurantOpen(restaurant.getOpeningTime(), restaurant.getClosingTime())) {
            throw new BusinessException("Restaurant is closed at this time");
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
        stockReservationService.reserveStock(cartItems);
        boolean stockReserved = stockReservationService.isEnabled();

        Address address = addressRepository.findByIdWithCustomer(request.getDeliveryAddressId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (!address.getCustomer().getId().equals(customerId)) {
            throw new BusinessException("Delivery address does not belong to customer");
        }

        try {
        OrderPricingService.OrderPricingResult pricing = orderPricingService.calculate(
                restaurant,
                cartItems,
                request.getCouponCode(),
                customer,
                request.getLoyaltyPointsToRedeem(),
                request.getPaymentMethod(),
                request.getWalletAmountToUse(),
                request.getUseWallet(),
                address.getLatitude(),
                address.getLongitude());

        Order order = new Order();
        order.setOrderNumber(generateOrderNumber());
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setDeliveryAddress(address);
        order.setStatus(scheduled ? Order.OrderStatus.SCHEDULED : Order.OrderStatus.PLACED);
        order.setScheduledAt(scheduled ? request.getScheduledAt() : null);
        order.setSpecialInstructions(request.getSpecialInstructions());
        order.setContactlessDelivery(Boolean.TRUE.equals(request.getContactlessDelivery()));
        order.setSubtotal(pricing.subtotal());
        order.setDeliveryFee(pricing.deliveryFee());
        order.setTaxAmount(pricing.taxAmount());
        order.setDiscountAmount(pricing.discountAmount());
        double tipAmount = request.getTipAmount() != null ? Math.max(0, request.getTipAmount()) : 0.0;
        tipAmount = PriceCalculator.roundToTwoDecimals(tipAmount);
        order.setTipAmount(tipAmount);
        order.setTotalAmount(PriceCalculator.roundToTwoDecimals(pricing.totalAmount() + tipAmount));
        order.setLoyaltyPointsRedeemed(pricing.loyaltyPointsRedeemed());
        order.setWalletAmountUsed(pricing.walletAmountUsed());
        order.setAppliedCoupon(pricing.appliedCoupon());

        int deliveryMinutes = restaurant.getAverageDeliveryTime() != null
                ? restaurant.getAverageDeliveryTime()
                : Constants.DEFAULT_DELIVERY_TIME;
        order.setEstimatedDeliveryTime(deliveryMinutes);
        order.setEstimatedDeliveryAt(LocalDateTime.now().plusMinutes(deliveryMinutes));

        order.setOrderItems(buildOrderItems(order, cartItems));

        orderEtaService.applyLiveEta(order);
        order = saveOrder(order);
        recordTimelineEvent(order.getId(), "ORDER_PLACED", order.getStatus().name(),
                "Order placed successfully", customerId, User.UserRole.CUSTOMER.name());
        decrementStock(cartItems);
        cartItems.forEach(item -> stockReservationService.syncStock(item.getMenuItem()));

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
            couponService.recordCouponUsage(pricing.appliedCoupon(), customerId, order.getId());
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
        } catch (RuntimeException ex) {
            if (stockReserved) {
                stockReservationService.releaseStock(cartItems);
            }
            throw ex;
        }
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
                log.warn("Failed to refund payment for cancelled order | orderId={} | paymentId={}", order.getId(), payment != null ? payment.getId() : null, ex);
                // Continue with cancellation even if refund fails
            }
        }

        orderMetrics.orderCancelled();
        log.info("Scheduled order cancelled | orderId={} | reason={}", orderId, reason);
        return response;
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
        return orderMapper.toResponse(order);
    }

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
        orderEtaService.applyLiveEta(order);
        order = saveOrder(order);
        recordTimelineEvent(order.getId(), "STATUS_CHANGE", newStatus.name(),
                "Order status changed from " + previousStatus + " to " + newStatus,
                resolveActorId(), resolveActorRole());
        publishAndInvalidate(order, previousStatus);
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

    private Map<Long, List<CartItem>> groupCartItemsByRestaurant(List<CartItem> cartItems) {
        Map<Long, List<CartItem>> grouped = new LinkedHashMap<>();
        for (CartItem item : cartItems) {
            Long restaurantId = item.getMenuItem().getCategory().getRestaurant().getId();
            grouped.computeIfAbsent(restaurantId, id -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private void decrementStock(List<CartItem> cartItems) {
        for (CartItem cartItem : cartItems) {
            MenuItem menuItem = cartItem.getMenuItem();
            if (menuItem.getStockQuantity() == null) {
                continue;
            }
            int remaining = menuItem.getStockQuantity() - cartItem.getQuantity();
            if (remaining < 0) {
                throw new BusinessException("Insufficient stock for: " + menuItem.getName());
            }
            menuItem.setStockQuantity(remaining);
            if (remaining == 0) {
                menuItem.setAvailable(false);
            }
            menuItemRepository.save(menuItem);
        }
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
