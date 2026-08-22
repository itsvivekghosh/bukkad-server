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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    /**
     * Creates a single order, replaying the cached response when the caller
     * supplies an idempotency key that has already completed successfully.
     */
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

    /**
     * Splits a multi-restaurant cart into one order per restaurant. Each group
     * is placed independently; failures in one group do not roll back the others
     * and are reported in the batch response.
     */
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
                log.warn("Batch order sub-order failed | customerId={} | restaurantId={} | error={}",
                        customerId, restaurantId, ex.getMessage());
                errors.add("Restaurant " + restaurantId + ": " + ex.getMessage());
                failureCount++;
            }
        }

        log.info("Batch order created | customerId={} | success={} | failure={} | totalOrders={}",
                customerId, successCount, failureCount, orders.size());

        return BatchOrderResponse.builder()
                .orders(orders)
                .successCount(successCount)
                .failureCount(failureCount)
                .errors(errors)
                .build();
    }

    /**
     * Core single-order creation. Validates the request, computes pricing,
     * persists the order, applies wallet/loyalty/coupon effects, creates the
     * payment, and clears the consumed cart items.
     */
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
            businessMetrics.checkout();
            businessMetrics.payment();
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
