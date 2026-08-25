package com.bhukkad.serviceImpl;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.dto.request.BatchOrderRequest;
import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.BatchOrderResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Cart;
import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderItem;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.User;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.idempotency.OrderIdempotencyService;
import com.bhukkad.inventory.StockReservationService;
import com.bhukkad.mapper.OrderMapper;
import com.bhukkad.metrics.BusinessMetrics;
import com.bhukkad.metrics.OrderMetrics;
import com.bhukkad.order.ScheduledOrderValidator;
import com.bhukkad.repository.AddressRepository;
import com.bhukkad.repository.CartItemRepository;
import com.bhukkad.repository.CartRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.restaurant.RestaurantBusyService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CouponService;
import com.bhukkad.service.OrderPricingService;
import com.bhukkad.service.PaymentService;
import com.bhukkad.timeline.OrderTimelineService;
import com.bhukkad.util.Constants;
import com.bhukkad.util.DateTimeUtils;
import com.bhukkad.util.PriceCalculator;
import com.bhukkad.wallet.WalletService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles the order creation pipeline: single orders and batch (multi-restaurant)
 * orders. Extracted from {@code OrderServiceImpl} so the order lifecycle class
 * stays focused on queries and status transitions.
 *
 * <p>Responsible for validating the cart/restaurant/address, computing pricing,
 * reserving stock, persisting the order, applying wallet/loyalty/coupon side
 * effects, creating the payment, and clearing the consumed cart items.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPlacementService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantRepository restaurantRepository;
    private final AddressRepository addressRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final MenuItemRepository menuItemRepository;
    private final SecurityUtils securityUtils;
    private final OrderCacheService orderCacheService;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderPricingService orderPricingService;
    private final CouponService couponService;
    private final PaymentService paymentService;
    private final OrderMapper orderMapper;
    private final OrderIdempotencyService orderIdempotencyService;
    private final OrderMetrics orderMetrics;
    private final BusinessMetrics businessMetrics;
    private final WalletService walletService;
    private final ScheduledOrderValidator scheduledOrderValidator;
    private final OrderEtaService orderEtaService;
    private final StockReservationService stockReservationService;
    private final OrderTimelineService orderTimelineService;
    private final RestaurantBusyService restaurantBusyService;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates a single order, replaying the cached response when the caller
     * supplies an idempotency key that has already completed successfully.
     *
     * <p>The order (with its PENDING payment row) is persisted in a short
     * transaction; for gateway payment methods the money capture is executed
     * <em>after</em> that transaction commits, so no JDBC connection is held
     * open during the external gateway round-trip.
     */
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
            OrderPlacementResult result = transactionTemplate.execute(status ->
                    doCreateOrder(request, idempotencyKey, customerId));
            if (result != null && result.needsGatewayProcessing()) {
                try {
                    Payment processed = paymentService.processPayment(
                            result.payment().getId(), result.paymentIdempotencyKey());
                    result.order().setPayment(processed);
                    OrderResponse response = orderMapper.toResponse(result.order());
                    orderIdempotencyService.completeOrderCreate(idempotencyKey, response);
                    return response;
                } catch (RuntimeException ex) {
                    // Gateway capture failed after the order committed and no
                    // money moved. Compensate so the order is not sent to the
                    // kitchen with an uncollectable payment.
                    compensateFailedPaymentOrder(result, customerId);
                    throw ex;
                }
            }
            orderIdempotencyService.completeOrderCreate(idempotencyKey, result != null ? result.response() : null);
            return result != null ? result.response() : null;
        } catch (RuntimeException ex) {
            orderIdempotencyService.failOrderCreate(idempotencyKey);
            throw ex;
        }
    }

    /**
     * Splits a multi-restaurant cart into one order per restaurant. Each group
     * is placed independently; failures in one group do not roll back the others
     * and are reported in the batch response.
     *
     * <p>The batch operation itself is idempotent: the {@code Idempotency-Key}
     * header (required by the controller) is claimed up-front, so a retried
     * request returns the original {@link BatchOrderResponse} instead of
     * creating a second set of duplicate orders.
     */
    public BatchOrderResponse createBatchOrders(BatchOrderRequest request, String idempotencyKey) {
        Long customerId = securityUtils.getCurrentUserId();
        if (StringUtils.hasText(idempotencyKey)) {
            var cached = orderIdempotencyService.findCompletedBatchResponse(idempotencyKey);
            if (cached.isPresent()) {
                return cached.get();
            }
            orderIdempotencyService.beginBatchOrderCreate(idempotencyKey, customerId);
        }

        try {
            Map<Long, List<CartItem>> byRestaurant = groupCartByRestaurant(customerId);
            double cartSubtotal = computeCartSubtotal(byRestaurant);
            double totalTip = safeTip(request.getTipAmount());

            List<OrderResponse> orders = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            int successCount = 0;
            int failureCount = 0;

            for (Map.Entry<Long, List<CartItem>> entry : byRestaurant.entrySet()) {
                Long restaurantId = entry.getKey();
                try {
                    OrderRequest orderRequest = buildBatchOrderRequest(
                            request, restaurantId, entry.getValue(), cartSubtotal, totalTip);
                    orders.add(placeSingleBatchOrder(orderRequest, customerId));
                    successCount++;
                } catch (RuntimeException ex) {
                    log.warn("Batch order sub-order failed | customerId={} | restaurantId={} | error={}",
                            customerId, restaurantId, ex.getMessage());
                    errors.add("Restaurant " + restaurantId + ": " + ex.getMessage());
                    failureCount++;
                }
            }

            log.info("Batch order created | customerId={} | success={} | failure={} | totalOrders={}",
                    customerId, successCount, failureCount, orders.size());

            BatchOrderResponse response = BatchOrderResponse.builder()
                    .orders(orders)
                    .successCount(successCount)
                    .failureCount(failureCount)
                    .errors(errors)
                    .build();
            orderIdempotencyService.completeBatchOrderCreate(idempotencyKey, response);
            return response;
        } catch (RuntimeException ex) {
            orderIdempotencyService.failBatchOrderCreate(idempotencyKey);
            throw ex;
        }
    }

    private Map<Long, List<CartItem>> groupCartByRestaurant(Long customerId) {
        Cart cart = cartRepository.findByCustomerIdWithRestaurant(customerId)
                .orElseThrow(() -> new BusinessException("Cart is empty"));
        List<CartItem> allItems = cartItemRepository.findByCartIdWithMenuItem(cart.getId());
        if (allItems.isEmpty()) {
            throw new BusinessException("Cart is empty");
        }
        return groupCartItemsByRestaurant(allItems);
    }

    private static double computeCartSubtotal(Map<Long, List<CartItem>> grouped) {
        return grouped.values().stream()
                .flatMap(List::stream)
                .mapToDouble(item -> PriceCalculator.calculateSubtotal(
                        item.getMenuItem().getPrice(), item.getQuantity()))
                .sum();
    }

    private static double safeTip(Double tip) {
        return tip != null ? Math.max(0, tip) : 0.0;
    }

    private static OrderRequest buildBatchOrderRequest(BatchOrderRequest request, Long restaurantId,
                                                        List<CartItem> items, double cartSubtotal, double totalTip) {
        double groupSubtotal = items.stream()
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
        return orderRequest;
    }

    private OrderResponse placeSingleBatchOrder(OrderRequest orderRequest, Long customerId) {
        OrderPlacementResult result = transactionTemplate.execute(
                status -> doCreateOrder(orderRequest, null, customerId));
        if (result == null) {
            return null;
        }
        if (result.needsGatewayProcessing()) {
            try {
                Payment processed = paymentService.processPayment(
                        result.payment().getId(), result.paymentIdempotencyKey());
                result.order().setPayment(processed);
                return orderMapper.toResponse(result.order());
            } catch (RuntimeException ex) {
                compensateFailedPaymentOrder(result, customerId);
                throw ex;
            }
        }
        return result.response();
    }

    /**
     * Core single-order creation. Validates the request, computes pricing,
     * persists the order, applies wallet/loyalty/coupon effects, creates the
     * payment (PENDING, no gateway call), and clears the consumed cart items.
     *
     * <p>Gateway payment processing is deferred to the caller ({@link #createOrder}
     * or {@link #createBatchOrders}) so that the external gateway I/O runs
     * outside the database transaction.
     */
    private OrderPlacementResult doCreateOrder(OrderRequest request, String idempotencyKey, Long customerId) {
        OrderCreationContext ctx = validateAndLoadOrderContext(request, customerId);
        Customer customer = ctx.customer();
        Restaurant restaurant = ctx.restaurant();
        Cart cart = ctx.cart();
        List<CartItem> cartItems = ctx.cartItems();
        Address address = ctx.address();
        boolean scheduled = ctx.scheduled();
        boolean stockReserved = stockReservationService.isEnabled();

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
            }

            // Loyalty points are earned once, when the order is DELIVERED
            // (see OrderStatusService#markOrderDelivered). Awarding them here at
            // placement would double-award the same order.
            if (pricing.walletAmountUsed() > 0 || pricing.loyaltyPointsRedeemed() > 0) {
                customerRepository.save(customer);
            }

            if (pricing.appliedCoupon() != null) {
                couponService.recordCouponUsage(pricing.appliedCoupon(), customerId, order.getId());
            }

            // Persist the PENDING payment row (no gateway call — the gateway
            // order is created lazily by GatewayPaymentStrategy#process which
            // runs after the DB transaction commits).
            String paymentMethod = normalizePaymentMethod(request.getPaymentMethod());
            String paymentIdempotencyKey = StringUtils.hasText(idempotencyKey)
                    ? "payment:" + idempotencyKey
                    : null;
            Payment payment = paymentService.createPayment(order.getId(), paymentMethod, paymentIdempotencyKey);
            order.setPayment(payment);

            clearCartItemsForRestaurant(cart, restaurant.getId());

            orderCacheService.invalidateOrder(order.getId(), customerId, restaurant.getId());
            orderEventPublisher.publishCreated(order);
            orderEventPublisher.publishItemsSnapshot(order);
            orderMetrics.orderCreated();
            businessMetrics.checkout();
            businessMetrics.payment();
            log.info("Order created | orderId={} | customerId={} | total={}",
                    order.getId(), customerId, order.getTotalAmount());
            OrderResponse response = orderMapper.toResponse(order);

            return new OrderPlacementResult(order, payment, response, paymentIdempotencyKey, cartItems);
        } catch (RuntimeException ex) {
            if (stockReserved) {
                stockReservationService.releaseStock(cartItems);
            }
            throw ex;
        }
    }

    /**
     * Validates the request and loads everything {@link #doCreateOrder} needs,
     * throwing on the first violated guard (restaurant inactive/closed/busy,
     * empty cart, missing/foreign address). Keeps the create path readable.
     */
    private OrderCreationContext validateAndLoadOrderContext(OrderRequest request, Long customerId) {
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

        orderPricingService.validateCartItems(restaurant, cartItems);
        stockReservationService.reserveStock(cartItems);

        Address address = addressRepository.findByIdWithCustomer(request.getDeliveryAddressId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (!address.getCustomer().getId().equals(customerId)) {
            throw new BusinessException("Delivery address does not belong to customer");
        }

        return new OrderCreationContext(customer, restaurant, cart, cartItems, address, scheduled);
    }

    /**
     * Everything {@link #doCreateOrder} needs after guard validation: the
     * loaded entities plus the computed flags, passed as one unit so the create
     * path stays flat and testable.
     */
    private record OrderCreationContext(Customer customer, Restaurant restaurant, Cart cart,
                                        List<CartItem> cartItems, Address address, boolean scheduled) {
    }

    // ==================== HELPERS ====================

    private Order saveOrder(Order order) {
        try {
            return orderRepository.save(order);
        } catch (OptimisticLockingFailureException ex) {
            throw new BusinessException("Order was updated by another request. Please retry.");
        }
    }

    private void recordTimelineEvent(Long orderId, String eventType, String status, String message,
                                     Long actorId, String actorRole) {
        try {
            orderTimelineService.recordEvent(orderId, eventType, status, message, actorId, actorRole);
        } catch (Exception ex) {
            log.warn("Failed to record order timeline event | orderId={} | type={}", orderId, eventType, ex);
        }
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
            // Single atomic UPDATE guarded by `stock_quantity >= quantity` at the
            // database. The row lock acquired by the UPDATE serialises concurrent
            // checkouts, so two requests reading stock=10 and both persisting 9
            // (the old read-modify-write oversell) can no longer happen.
            int updated = menuItemRepository.decrementStockAtomic(
                    menuItem.getId(), cartItem.getQuantity());
            if (updated == 0) {
                throw new BusinessException("Insufficient stock for: " + menuItem.getName());
            }
            // The bulk UPDATE bypasses the persistence context; re-read the row so
            // the managed entity (and the Redis stock sync below) sees the
            // post-decrement value instead of a stale snapshot.
            entityManager.refresh(menuItem);
            if (menuItem.getStockQuantity() != null && menuItem.getStockQuantity() <= 0) {
                menuItem.setAvailable(false);
            }
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
        List<CartItem> toDelete = new ArrayList<>();
        List<CartItem> remaining = new ArrayList<>();
        for (CartItem item : items) {
            if (item.getMenuItem().getCategory().getRestaurant().getId().equals(restaurantId)) {
                toDelete.add(item);
            } else {
                remaining.add(item);
            }
        }
        if (!toDelete.isEmpty()) {
            cartItemRepository.deleteAll(toDelete);
        }
        if (remaining.isEmpty()) {
            cart.setRestaurant(null);
        } else if (cart.getRestaurant() != null && cart.getRestaurant().getId().equals(restaurantId)) {
            // Restaurant is already JOIN FETCHed on the items — no extra lookup
            cart.setRestaurant(remaining.get(0).getMenuItem().getCategory().getRestaurant());
        }
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

    /**
     * Compensation for an order whose gateway payment failed <em>after</em> the
     * order transaction committed. The order is cancelled and the reserved stock
     * is restored so the failed order is never sent to the kitchen and stock is
     * not leaked. Best-effort: a failure here is logged and surfaced via the
     * payment's FAILED status / dunning loop rather than masking the original
     * payment error.
     */
    private void compensateFailedPaymentOrder(OrderPlacementResult result, Long customerId) {
        try {
            Order order = result.order();
            order.setStatus(Order.OrderStatus.CANCELLED);
            order.setCancellationReason("Payment failed");
            orderRepository.save(order);
            orderCacheService.invalidateOrder(
                    order.getId(), customerId,
                    order.getRestaurant() != null ? order.getRestaurant().getId() : null);
            for (CartItem cartItem : result.cartItems()) {
                MenuItem menuItem = cartItem.getMenuItem();
                if (menuItem.getStockQuantity() == null) {
                    continue;
                }
                menuItemRepository.restoreStockAtomic(menuItem.getId(), cartItem.getQuantity());
                entityManager.refresh(menuItem);
                stockReservationService.syncStock(menuItem);
            }
            log.info("Order cancelled as payment-failure compensation | orderId={}", order.getId());
        } catch (Exception ex) {
            log.warn("Payment-failure compensation failed | orderId={} | error={}",
                    result.order().getId(), ex.getMessage());
        }
    }

    /**
     * Result of {@link #doCreateOrder}: the persisted order, the PENDING payment
     * row, the response built inside the transaction, and the idempotency key to
     * use for the deferred gateway processing.
     */
    private record OrderPlacementResult(Order order, Payment payment, OrderResponse response,
                                        String paymentIdempotencyKey, List<CartItem> cartItems) {

        boolean needsGatewayProcessing() {
            return payment.getPaymentMethod() != Payment.PaymentMethod.CASH_ON_DELIVERY;
        }
    }
}
