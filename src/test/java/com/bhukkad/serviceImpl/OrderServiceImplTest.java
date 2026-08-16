package com.bhukkad.serviceImpl;

import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.OrderSummaryResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Cart;
import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.User;
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
import com.bhukkad.delivery.DeliveryProofService;
import com.bhukkad.delivery.RiderDispatchService;
import com.bhukkad.delivery.RiderEarningService;
import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.order.ScheduledOrderValidator;
import com.bhukkad.invoice.OrderInvoiceService;
import com.bhukkad.restaurant.RestaurantBusyService;
import com.bhukkad.timeline.OrderTimelineService;
import com.bhukkad.settlement.RestaurantSettlementService;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.wallet.WalletService;
import com.bhukkad.idempotency.OrderIdempotencyService;
import com.bhukkad.metrics.OrderMetrics;
import com.bhukkad.mapper.OrderMapper;
import com.bhukkad.service.CouponService;
import com.bhukkad.service.DeliveryService;
import com.bhukkad.service.OrderPricingService;
import com.bhukkad.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private DeliveryAgentRepository deliveryAgentRepository;
    @Mock
    private CartRepository cartRepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private OrderCacheService orderCacheService;
    @Mock
    private RedisCacheService redisCacheService;
    @Mock
    private OrderEventPublisher orderEventPublisher;
    @Mock
    private OrderPricingService orderPricingService;
    @Mock
    private CouponService couponService;
    @Mock
    private PaymentService paymentService;
    @Mock
    private DeliveryService deliveryService;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderIdempotencyService orderIdempotencyService;
    @Mock
    private RiderDispatchService riderDispatchService;
    @Mock
    private OrderMetrics orderMetrics;
    @Mock
    private WalletService walletService;
    @Mock
    private RiderEarningService riderEarningService;
    @Mock
    private MenuItemRepository menuItemRepository;
    @Mock
    private ScheduledOrderValidator scheduledOrderValidator;
    @Mock
    private OrderEtaService orderEtaService;
    @Mock
    private RestaurantSettlementService restaurantSettlementService;
    @Mock
    private com.bhukkad.inventory.StockReservationService stockReservationService;
    @Mock
    private OrderTimelineService orderTimelineService;
    @Mock
    private OrderInvoiceService orderInvoiceService;
    @Mock
    private RestaurantBusyService restaurantBusyService;
    @Mock
    private DeliveryProofService deliveryProofService;

    @InjectMocks
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        lenient().doNothing().when(orderEventPublisher).publishStatusChange(any(), any());
        lenient().doNothing().when(orderEventPublisher).publishCreated(any());
        lenient().doNothing().when(orderEventPublisher).publishAgentAssigned(any());
        lenient().doNothing().when(orderCacheService).invalidateOrder(anyLong(), any(), any());
        lenient().doNothing().when(orderEtaService).applyLiveEta(any(Order.class));
        lenient().doNothing().when(scheduledOrderValidator).validateScheduledAt(any());
        lenient().when(scheduledOrderValidator.isScheduledOrder(any())).thenReturn(false);
        lenient().doNothing().when(restaurantSettlementService).recordSettlementForDeliveredOrder(any(Order.class));
        lenient().doNothing().when(stockReservationService).reserveStock(anyList());
        lenient().doNothing().when(restaurantBusyService).assertAcceptingOrders(anyLong());
        lenient().when(orderTimelineService.recordEvent(anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        lenient().when(orderInvoiceService.generateOnDelivery(any(Order.class))).thenReturn(null);
        lenient().doNothing().when(stockReservationService).releaseStock(anyList());
        lenient().doNothing().when(stockReservationService).syncStock(any(MenuItem.class));
        lenient().when(stockReservationService.isEnabled()).thenReturn(false);
        lenient().when(orderMapper.toResponse(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            Payment payment = order.getPayment();
            return OrderResponse.builder()
                    .id(order.getId())
                    .orderNumber(order.getOrderNumber())
                    .customerId(order.getCustomer() != null ? order.getCustomer().getId() : null)
                    .customerName(order.getCustomer() != null ? order.getCustomer().getFullName() : null)
                    .restaurantId(order.getRestaurant() != null ? order.getRestaurant().getId() : null)
                    .restaurantName(order.getRestaurant() != null ? order.getRestaurant().getName() : null)
                    .status(order.getStatus() != null ? order.getStatus().name() : null)
                    .subtotal(order.getSubtotal())
                    .deliveryFee(order.getDeliveryFee())
                    .taxAmount(order.getTaxAmount())
                    .discountAmount(order.getDiscountAmount())
                    .totalAmount(order.getTotalAmount())
                    .paymentMethod(payment != null ? payment.getPaymentMethod().name() : null)
                    .paymentStatus(payment != null ? payment.getStatus().name() : null)
                    .createdAt(order.getCreatedAt())
                    .estimatedDeliveryTime(order.getEstimatedDeliveryTime())
                    .estimatedDeliveryAt(order.getEstimatedDeliveryAt())
                    .deliveredAt(order.getDeliveredAt())
                    .specialInstructions(order.getSpecialInstructions())
                    .contactlessDelivery(order.getContactlessDelivery())
                    .build();
        });
    }

    // ==================== createOrder ====================

    @Test
    void createOrder_customerNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> orderService.createOrder(orderRequest(), null));
        assertEquals("Customer not found", ex.getMessage());
    }

    @Test
    void createOrder_restaurantNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L, "Ada")));
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> orderService.createOrder(orderRequest(), null));
        assertEquals("Restaurant not found", ex.getMessage());
    }

    @Test
    void createOrder_addressNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L, "Ada")));
        Restaurant restaurant = activeRestaurant(10L, "Cafe");
        Cart cart = cartWithItems(restaurant);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(cart.getId()))
                .thenReturn(cartItems(restaurant, 100.0, 1));

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> orderService.createOrder(orderRequest(), null));
        assertEquals("Address not found", ex.getMessage());
    }

    @Test
    void createOrder_addressBelongsToAnotherCustomer_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L, "Ada")));
        Restaurant restaurant = activeRestaurant(10L, "Cafe");
        Cart cart = cartWithItems(restaurant);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(cart.getId()))
                .thenReturn(cartItems(restaurant, 100.0, 1));
        Address address = address(20L, customer(2L, "Bob"));
        when(addressRepository.findByIdWithCustomer(20L)).thenReturn(Optional.of(address));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(orderRequest(), null));
        assertEquals("Delivery address does not belong to customer", ex.getMessage());
    }

    @Test
    void createOrder_emptyCart_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L, "Ada")));
        Restaurant restaurant = activeRestaurant(10L, "Cafe");
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(orderRequest(), null));
        assertEquals("Cart is empty", ex.getMessage());
    }

    @Test
    void createOrder_success_buildsOrderFromCartWithPricing() {
        Customer customer = customer(1L, "Ada Lovelace");
        Restaurant restaurant = activeRestaurant(10L, "Cafe Aroma");
        restaurant.setDeliveryFee(40.0);
        Address address = address(20L, customer);
        Cart cart = cartWithItems(restaurant);
        List<CartItem> cartItems = cartItems(restaurant, 100.0, 2);

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(cart.getId())).thenReturn(cartItems);
        when(addressRepository.findByIdWithCustomer(20L)).thenReturn(Optional.of(address));
        when(orderPricingService.calculate(any(), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new OrderPricingService.OrderPricingResult(
                        200.0, 40.0, 10.0, 0.0, 0.0, 0, 0.0, 250.0, 250.0, null));

        Payment payment = new Payment();
        payment.setPaymentMethod(Payment.PaymentMethod.CASH_ON_DELIVERY);
        payment.setStatus(Payment.PaymentStatus.PENDING);
        when(paymentService.createPayment(anyLong(), eq("CASH_ON_DELIVERY"), isNull())).thenReturn(payment);

        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 14, 21, 0);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order saved = inv.getArgument(0);
            saved.setId(100L);
            saved.setCreatedAt(createdAt);
            return saved;
        });

        OrderRequest request = orderRequest();
        request.setSpecialInstructions("No onions");
        OrderResponse response = orderService.createOrder(request, null);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        Order persisted = captor.getValue();
        assertTrue(persisted.getOrderNumber().matches("ORD-[0-9A-F]{8}"));
        assertEquals(Order.OrderStatus.PLACED, persisted.getStatus());
        assertEquals(200.0, persisted.getSubtotal());
        assertEquals(40.0, persisted.getDeliveryFee());
        assertEquals(10.0, persisted.getTaxAmount());
        assertEquals(250.0, persisted.getTotalAmount());
        assertEquals(1, persisted.getOrderItems().size());
        assertEquals(100.0, persisted.getOrderItems().get(0).getPrice());
        assertEquals(2, persisted.getOrderItems().get(0).getQuantity());

        assertEquals(100L, response.getId());
        assertEquals("PLACED", response.getStatus());
        assertEquals(250.0, response.getTotalAmount());
        assertEquals("CASH_ON_DELIVERY", response.getPaymentMethod());
        assertEquals("PENDING", response.getPaymentStatus());
        assertEquals("No onions", response.getSpecialInstructions());

        verify(cartItemRepository).delete(any(CartItem.class));
        verify(cartRepository).save(cart);
        verify(orderEventPublisher).publishCreated(any(Order.class));
    }

    // ==================== getOrderById ====================

    @Test
    void getOrderById_notFound_throws() {
        when(orderRepository.findByIdWithDetails(5L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> orderService.getOrderById(5L));
        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    void getOrderById_notOwner_throws() {
        Order order = detailedOrder(5L, Order.OrderStatus.CONFIRMED);
        when(orderRepository.findByIdWithDetails(5L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(99L);

        assertThrows(UnauthorizedException.class, () -> orderService.getOrderById(5L));
    }

    @Test
    void getOrderById_mapsAllResponseFields() {
        Order order = detailedOrder(5L, Order.OrderStatus.CONFIRMED);
        when(orderRepository.findByIdWithDetails(5L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        OrderResponse response = orderService.getOrderById(5L);

        assertEquals(5L, response.getId());
        assertEquals("ORD-ABCDEF12", response.getOrderNumber());
        assertEquals("Ada Lovelace", response.getCustomerName());
        assertEquals("Cafe Aroma", response.getRestaurantName());
        assertEquals("CONFIRMED", response.getStatus());
        assertEquals(200.0, response.getSubtotal());
        assertEquals(20.0, response.getDeliveryFee());
        assertEquals(10.0, response.getTaxAmount());
        assertEquals(15.0, response.getDiscountAmount());
        assertEquals(215.0, response.getTotalAmount());
        assertEquals(order.getCreatedAt(), response.getCreatedAt());
        assertEquals("Ring doorbell", response.getSpecialInstructions());
    }

    // ==================== list queries ====================

    @Test
    void getCustomerOrders_mapsPagedSummaries() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        Page<OrderSummaryResponse> page = new PageImpl<>(List.of(orderSummary(1L, Order.OrderStatus.PLACED)));
        when(orderRepository.findCustomerOrderSummaries(eq(1L), any(Pageable.class))).thenReturn(page);

        PagedResponse<OrderSummaryResponse> result = orderService.getCustomerOrders(0, 20);

        assertEquals(1, result.getItems().size());
        assertEquals("Ada Lovelace", result.getItems().get(0).getCustomerName());
        assertEquals("PLACED", result.getItems().get(0).getStatus());
    }

    @Test
    void getRestaurantOrders_mapsPagedSummaries() {
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant(10L, "Cafe Aroma")));
        Page<OrderSummaryResponse> page = new PageImpl<>(List.of(orderSummary(2L, Order.OrderStatus.PREPARING)));
        when(orderRepository.findRestaurantOrderSummaries(eq(10L), any(Pageable.class))).thenReturn(page);

        PagedResponse<OrderSummaryResponse> result = orderService.getRestaurantOrders(10L, 0, 20);

        assertEquals(1, result.getItems().size());
        assertEquals("Cafe Aroma", result.getItems().get(0).getRestaurantName());
        assertEquals("PREPARING", result.getItems().get(0).getStatus());
    }

    @Test
    void getPendingOrdersForRestaurant_usesPlacedStatus() {
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant(10L, "Cafe Aroma")));
        when(orderRepository.findPendingSummariesForRestaurant(eq(10L), eq(Order.OrderStatus.PLACED), any(Pageable.class)))
                .thenReturn(List.of(orderSummary(3L, Order.OrderStatus.PLACED)));

        List<OrderSummaryResponse> result = orderService.getPendingOrdersForRestaurant(10L, 20);

        assertEquals(1, result.size());
        assertEquals("PLACED", result.get(0).getStatus());
    }

    // ==================== status transitions ====================

    @Test
    void cancelOrder_notFound_throws() {
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> orderService.cancelOrder(1L, "changed mind"));
    }

    @Test
    void cancelOrder_setsCancelled() {
        Order order = detailedOrder(1L, Order.OrderStatus.PLACED);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(orderRepository.save(order)).thenReturn(order);

        OrderResponse response = orderService.cancelOrder(1L, "changed mind");

        assertEquals(Order.OrderStatus.CANCELLED, order.getStatus());
        assertEquals("CANCELLED", response.getStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void cancelOrder_deliveredStatus_throws() {
        Order order = detailedOrder(1L, Order.OrderStatus.DELIVERED);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        assertThrows(BusinessException.class, () -> orderService.cancelOrder(1L, "too late"));
    }

    @Test
    void acceptOrder_notFound_throws() {
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> orderService.acceptOrder(1L));
    }

    @Test
    void acceptOrder_setsConfirmed() {
        Order order = detailedOrder(1L, Order.OrderStatus.PLACED);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(orderRepository.save(order)).thenReturn(order);

        OrderResponse response = orderService.acceptOrder(1L);

        assertEquals(Order.OrderStatus.CONFIRMED, order.getStatus());
        assertEquals("CONFIRMED", response.getStatus());
    }

    @Test
    void acceptOrder_optimisticLockConflict_throwsBusinessException() {
        Order order = detailedOrder(1L, Order.OrderStatus.PLACED);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(orderRepository.save(order)).thenThrow(new OptimisticLockingFailureException("conflict"));

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.acceptOrder(1L));
        assertEquals("Order was updated by another request. Please retry.", ex.getMessage());
    }

    @Test
    void markOrderReady_setsReadyForPickup() {
        Order order = detailedOrder(1L, Order.OrderStatus.PREPARING);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(orderRepository.save(order)).thenReturn(order);
        when(riderDispatchService.autoAssignNearestRider(any())).thenReturn(Optional.empty());

        OrderResponse response = orderService.markOrderReady(1L);

        assertEquals(Order.OrderStatus.READY_FOR_PICKUP, order.getStatus());
        assertEquals("READY_FOR_PICKUP", response.getStatus());
    }

    // ==================== delivery operations ====================

    @Test
    void getDeliveryAgentOrders_usesCurrentAgent() {
        DeliveryAgent agent = deliveryAgent(3L);
        OrderSummaryResponse summary = new OrderSummaryResponse(
                4L, "ORD-ABCDEF12", 1L, "Ada Lovelace", 10L, "Cafe Aroma",
                Order.OrderStatus.OUT_FOR_DELIVERY, 215.0, "Ring doorbell",
                LocalDateTime.of(2026, 8, 14, 12, 30), null);
        when(securityUtils.getCurrentUser()).thenReturn(agent);
        when(orderRepository.findDeliveryAgentOrderSummaries(eq(3L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary)));

        var result = orderService.getDeliveryAgentOrders(null, 0, 20);

        assertEquals(1, result.getItems().size());
        assertEquals("OUT_FOR_DELIVERY", result.getItems().get(0).getStatus());
    }

    @Test
    void assignDeliveryAgent_setsAgent() {
        Order order = detailedOrder(1L, Order.OrderStatus.READY_FOR_PICKUP);
        DeliveryAgent agent = deliveryAgent(7L);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(deliveryAgentRepository.findById(7L)).thenReturn(Optional.of(agent));
        when(orderRepository.save(order)).thenReturn(order);

        orderService.assignDeliveryAgent(1L, 7L);

        assertEquals(agent, order.getDeliveryAgent());
        verify(orderEventPublisher).publishAgentAssigned(order);
    }

    @Test
    void markOrderDelivered_setsDeliveredAt() {
        DeliveryAgent agent = deliveryAgent(3L);
        Order order = detailedOrder(1L, Order.OrderStatus.OUT_FOR_DELIVERY);
        order.setDeliveryAgent(agent);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUser()).thenReturn(agent);
        when(orderRepository.save(order)).thenReturn(order);

        OrderResponse response = orderService.markOrderDelivered(1L);

        assertEquals(Order.OrderStatus.DELIVERED, order.getStatus());
        assertNotNull(order.getDeliveredAt());
        assertEquals("DELIVERED", response.getStatus());
    }

    @Test
    void getOrderByNumber_delegatesAndChecksOwnership() {
        Order order = detailedOrder(6L, Order.OrderStatus.PLACED);
        when(orderRepository.findByOrderNumberWithDetails("ORD-ABCDEF12")).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        OrderResponse response = orderService.getOrderByNumber("ORD-ABCDEF12");

        assertEquals(6L, response.getId());
    }

    @Test
    void getKitchenActiveOrders_cacheHit_returnsCachedQueue() {
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant(10L, "Cafe Aroma")));
        List<OrderSummaryResponse> cached = List.of(orderSummary(1L, Order.OrderStatus.CONFIRMED));
        when(redisCacheService.getList(CacheKeyGenerator.kitchenQueue(10L), OrderSummaryResponse.class))
                .thenReturn(Optional.of(cached));

        List<OrderSummaryResponse> result = orderService.getKitchenActiveOrders(10L, 20);

        assertEquals(cached, result);
        verify(orderRepository, never()).findKitchenActiveSummaries(anyLong(), anyList(), any(Pageable.class));
    }

    @Test
    void trackOrder_cacheHit_returnsWithoutDbLookup() {
        OrderResponse cached = OrderResponse.builder().id(8L).status("PLACED").build();
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(orderCacheService.getTrackedOrder(8L, 1L)).thenReturn(Optional.of(cached));

        OrderResponse response = orderService.trackOrder(8L);

        assertEquals(8L, response.getId());
        verify(orderRepository, never()).findByIdWithDetails(anyLong());
    }

    @Test
    void getDeliveryAgentOrders_otherAgent_throws() {
        when(securityUtils.getCurrentUser()).thenReturn(deliveryAgent(3L));
        assertThrows(UnauthorizedException.class, () -> orderService.getDeliveryAgentOrders(99L, 0, 20));
    }

    @Test
    void updateOrderStatus_updatesForRestaurantOwner() {
        Order order = detailedOrder(1L, Order.OrderStatus.PLACED);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(orderRepository.save(order)).thenReturn(order);

        OrderResponse response = orderService.updateOrderStatus(1L, Order.OrderStatus.PREPARING);

        assertEquals("PREPARING", response.getStatus());
    }

    @Test
    void confirmOrder_delegatesToAccept() {
        Order order = detailedOrder(1L, Order.OrderStatus.PLACED);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(orderRepository.save(order)).thenReturn(order);

        assertEquals("CONFIRMED", orderService.confirmOrder(1L).getStatus());
    }

    @Test
    void getKitchenActiveOrders_cacheMiss_loadsAndCaches() {
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant(10L, "Cafe Aroma")));
        when(redisCacheService.getList(CacheKeyGenerator.kitchenQueue(10L), OrderSummaryResponse.class))
                .thenReturn(Optional.empty());
        List<OrderSummaryResponse> queue = List.of(orderSummary(1L, Order.OrderStatus.CONFIRMED));
        when(orderRepository.findKitchenActiveSummaries(eq(10L), anyList(), any(Pageable.class))).thenReturn(queue);

        List<OrderSummaryResponse> result = orderService.getKitchenActiveOrders(10L, 20);

        assertEquals(queue, result);
        verify(redisCacheService).set(eq(CacheKeyGenerator.kitchenQueue(10L)), eq(queue), anyLong());
    }

    @Test
    void assignDeliveryAgent_agentNotFound_throws() {
        Order order = detailedOrder(1L, Order.OrderStatus.READY_FOR_PICKUP);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(deliveryAgentRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.assignDeliveryAgent(1L, 7L));
    }

    @Test
    void updateDeliveryStatus_invalidStatus_throws() {
        DeliveryAgent agent = deliveryAgent(3L);
        Order order = detailedOrder(1L, Order.OrderStatus.READY_FOR_PICKUP);
        order.setDeliveryAgent(agent);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUser()).thenReturn(agent);

        assertThrows(BusinessException.class,
                () -> orderService.updateDeliveryStatus(1L, Order.OrderStatus.DELIVERED));
    }

    @Test
    void updateDeliveryStatus_success() {
        DeliveryAgent agent = deliveryAgent(3L);
        Order order = detailedOrder(1L, Order.OrderStatus.READY_FOR_PICKUP);
        order.setDeliveryAgent(agent);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUser()).thenReturn(agent);
        when(orderRepository.save(order)).thenReturn(order);

        assertEquals("OUT_FOR_DELIVERY",
                orderService.updateDeliveryStatus(1L, Order.OrderStatus.OUT_FOR_DELIVERY).getStatus());
    }

    @Test
    void getRestaurantOrders_restaurantNotFound_throws() {
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> orderService.getRestaurantOrders(10L, 0, 20));
    }

    @Test
    void acceptOrder_notOwner_throws() {
        Order order = detailedOrder(1L, Order.OrderStatus.PLACED);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(99L);

        assertThrows(UnauthorizedException.class, () -> orderService.acceptOrder(1L));
    }

    @Test
    void markOrderDelivered_notAssignedToAgent_throws() {
        Order order = detailedOrder(1L, Order.OrderStatus.OUT_FOR_DELIVERY);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUser()).thenReturn(deliveryAgent(3L));

        assertThrows(UnauthorizedException.class, () -> orderService.markOrderDelivered(1L));
    }

    @Test
    void getOrderByNumber_notFound_throws() {
        when(orderRepository.findByOrderNumberWithDetails("ORD-MISSING")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> orderService.getOrderByNumber("ORD-MISSING"));
    }

    @Test
    void markOrderDelivered_wrongAgent_throws() {
        DeliveryAgent assigned = deliveryAgent(5L);
        Order order = detailedOrder(1L, Order.OrderStatus.OUT_FOR_DELIVERY);
        order.setDeliveryAgent(assigned);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUser()).thenReturn(deliveryAgent(3L));

        assertThrows(UnauthorizedException.class, () -> orderService.markOrderDelivered(1L));
    }

    @Test
    void updateDeliveryStatus_notDeliveryAgentRole_throws() {
        Customer customer = customer(3L, "Agent");
        customer.setRole(User.UserRole.CUSTOMER);
        Order order = detailedOrder(1L, Order.OrderStatus.READY_FOR_PICKUP);
        order.setDeliveryAgent(deliveryAgent(3L));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUser()).thenReturn(customer);

        assertThrows(UnauthorizedException.class,
                () -> orderService.updateDeliveryStatus(1L, Order.OrderStatus.OUT_FOR_DELIVERY));
    }

    // ==================== scheduled orders ====================

    @Test
    void getCustomerScheduledOrders_returnsPagedScheduledOrders() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        OrderSummaryResponse summary = new OrderSummaryResponse(
                10L, "ORD-SCHED123", 1L, "Ada Lovelace", 5L, "Cafe Aroma",
                Order.OrderStatus.SCHEDULED, 150.0, "No onions",
                LocalDateTime.of(2026, 8, 15, 12, 0),
                LocalDateTime.of(2026, 8, 15, 12, 30));
        Page<OrderSummaryResponse> page = new PageImpl<>(List.of(summary));
        when(orderRepository.findCustomerScheduledOrderSummaries(eq(1L), any(Pageable.class))).thenReturn(page);

        PagedResponse<OrderSummaryResponse> result = orderService.getCustomerScheduledOrders(0, 20);

        assertEquals(1, result.getItems().size());
        assertEquals(Order.OrderStatus.SCHEDULED.name(), result.getItems().get(0).getStatus());
        assertEquals("No onions", result.getItems().get(0).getSpecialInstructions());
        verify(orderRepository).findCustomerScheduledOrderSummaries(eq(1L), any(Pageable.class));
    }

    @Test
    void getCustomerScheduledOrdersByCursor_returnsCursorPagedScheduledOrders() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        OrderSummaryResponse summary = new OrderSummaryResponse(
                10L, "ORD-SCHED123", 1L, "Ada Lovelace", 5L, "Cafe Aroma",
                Order.OrderStatus.SCHEDULED, 150.0, "No onions",
                LocalDateTime.of(2026, 8, 15, 12, 0),
                LocalDateTime.of(2026, 8, 15, 12, 30));
        when(orderRepository.findCustomerScheduledOrderSummariesAfterCursor(
                eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(summary));

        CursorPagedResponse<OrderSummaryResponse> result = orderService.getCustomerScheduledOrdersByCursor(null, 20);

        assertEquals(1, result.getItems().size());
        assertEquals(Order.OrderStatus.SCHEDULED.name(), result.getItems().get(0).getStatus());
        verify(orderRepository).findCustomerScheduledOrderSummariesAfterCursor(
                eq(1L), any(), any(), any(Pageable.class));
    }

    @Test
    void cancelScheduledOrder_notFound_throws() {
        when(orderRepository.findByIdWithDetails(5L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> orderService.cancelScheduledOrder(5L, "changed mind"));
    }

    @Test
    void cancelScheduledOrder_notOwner_throws() {
        Order order = scheduledOrder(1L, customer(2L, "Bob"));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        assertThrows(UnauthorizedException.class, () -> orderService.cancelScheduledOrder(1L, "changed mind"));
    }

    @Test
    void cancelScheduledOrder_notScheduledStatus_throws() {
        Order order = detailedOrder(1L, Order.OrderStatus.PLACED);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancelScheduledOrder(1L, "changed mind"));
        assertEquals("Only scheduled orders can be cancelled via this endpoint", ex.getMessage());
    }

    @Test
    void cancelScheduledOrder_success_refundsAndRestoresPoints() {
        Customer customer = customer(1L, "Ada");
        customer.setLoyaltyPoints(50);
        Order order = scheduledOrder(1L, customer);
        order.setLoyaltyPointsRedeemed(20);
        order.setTotalAmount(200.0);

        Payment payment = new Payment();
        payment.setPaymentMethod(Payment.PaymentMethod.CASH_ON_DELIVERY);
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        order.setPayment(payment);

        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(orderRepository.save(any(Order.class))).thenReturn(order);
        when(customerRepository.save(customer)).thenReturn(customer);

        OrderResponse response = orderService.cancelScheduledOrder(1L, "changed mind");

        assertEquals(Order.OrderStatus.CANCELLED, order.getStatus());
        assertEquals("CANCELLED", response.getStatus());
        assertEquals("changed mind", order.getCancellationReason());
        assertEquals(User.UserRole.CUSTOMER.name(), order.getCancelledBy());
        assertEquals(70, customer.getLoyaltyPoints()); // 50 + 20 restored
        verify(paymentService).refundPayment(payment.getId());
        verify(orderEventPublisher).publishStatusChange(any(), any());
    }

    @Test
    void cancelScheduledOrder_pendingPayment_noRefund() {
        Customer customer = customer(1L, "Ada");
        Order order = scheduledOrder(1L, customer);
        Payment payment = new Payment();
        payment.setPaymentMethod(Payment.PaymentMethod.CASH_ON_DELIVERY);
        payment.setStatus(Payment.PaymentStatus.PENDING);
        order.setPayment(payment);

        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        OrderResponse response = orderService.cancelScheduledOrder(1L, "changed mind");

        assertEquals(Order.OrderStatus.CANCELLED, order.getStatus());
        verify(paymentService, never()).refundPayment(anyLong());
    }

    // ==================== helpers ====================

    private Order scheduledOrder(Long id, Customer customer) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNumber("ORD-SCHED123");
        order.setCustomer(customer);
        order.setRestaurant(restaurant(5L, "Cafe Aroma"));
        order.setDeliveryAddress(address(20L, customer));
        order.setStatus(Order.OrderStatus.SCHEDULED);
        order.setScheduledAt(LocalDateTime.now().plusHours(2));
        order.setSubtotal(100.0);
        order.setDeliveryFee(20.0);
        order.setTaxAmount(10.0);
        order.setTotalAmount(130.0);
        order.setCreatedAt(LocalDateTime.of(2026, 8, 15, 10, 0));
        order.setSpecialInstructions("No onions");
        return order;
    }

    // ==================== trackOrder ====================

    @Test
    void trackOrder_usesCacheWhenPresent() {
        OrderResponse cached = OrderResponse.builder().id(8L).status("OUT_FOR_DELIVERY").build();
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(orderCacheService.getTrackedOrder(8L, 1L)).thenReturn(Optional.of(cached));

        OrderResponse response = orderService.trackOrder(8L);

        assertEquals(8L, response.getId());
        verify(orderRepository, never()).findByIdWithDetails(any());
    }

    @Test
    void trackOrder_delegatesToGetOrderById() {
        Order order = detailedOrder(8L, Order.OrderStatus.OUT_FOR_DELIVERY);
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(orderCacheService.getTrackedOrder(8L, 1L)).thenReturn(Optional.empty());
        when(orderRepository.findByIdWithDetails(8L)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.trackOrder(8L);

        assertEquals(8L, response.getId());
        assertEquals("OUT_FOR_DELIVERY", response.getStatus());
        verify(orderEtaService).applyLiveEta(order);
        verify(orderCacheService).cacheTrackedOrder(eq(8L), eq(1L), any(OrderResponse.class));
    }

    @Test
    void trackOrder_nonOwner_throwsAndDoesNotCache() {
        Order order = detailedOrder(8L, Order.OrderStatus.OUT_FOR_DELIVERY);
        when(securityUtils.getCurrentUserId()).thenReturn(99L);
        when(orderCacheService.getTrackedOrder(8L, 99L)).thenReturn(Optional.empty());
        when(orderRepository.findByIdWithDetails(8L)).thenReturn(Optional.of(order));

        assertThrows(UnauthorizedException.class, () -> orderService.trackOrder(8L));

        verify(orderCacheService, never()).cacheTrackedOrder(any(), any(), any());
        verify(orderEtaService, never()).applyLiveEta(any());
    }

    // ==================== helpers ====================

    private OrderRequest orderRequest() {
        OrderRequest request = new OrderRequest();
        request.setRestaurantId(10L);
        request.setDeliveryAddressId(20L);
        request.setPaymentMethod("COD");
        return request;
    }

    private Restaurant activeRestaurant(Long id, String name) {
        Restaurant restaurant = restaurant(id, name);
        restaurant.setIsActive(true);
        restaurant.setIsOpen(true);
        return restaurant;
    }

    private Cart cartWithItems(Restaurant restaurant) {
        Cart cart = new Cart();
        cart.setId(5L);
        cart.setRestaurant(restaurant);
        return cart;
    }

    private List<CartItem> cartItems(Restaurant restaurant, double price, int quantity) {
        MenuCategory category = new MenuCategory();
        category.setRestaurant(restaurant);

        MenuItem menuItem = new MenuItem();
        menuItem.setId(50L);
        menuItem.setName("Burger");
        menuItem.setPrice(price);
        menuItem.setAvailable(true);
        menuItem.setCategory(category);

        CartItem cartItem = new CartItem();
        cartItem.setMenuItem(menuItem);
        cartItem.setQuantity(quantity);
        return List.of(cartItem);
    }

    private Customer customer(Long id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setFullName(name);
        return customer;
    }

    private Restaurant restaurant(Long id, String name) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        restaurant.setName(name);
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(9L);
        restaurant.setOwner(owner);
        return restaurant;
    }

    private Address address(Long id, Customer customer) {
        Address address = new Address();
        address.setId(id);
        address.setCustomer(customer);
        address.setAddressLine1("12 MG Road");
        address.setCity("Bengaluru");
        return address;
    }

    private DeliveryAgent deliveryAgent(Long id) {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(id);
        agent.setRole(User.UserRole.DELIVERY_AGENT);
        return agent;
    }

    private OrderSummaryResponse orderSummary(Long id, Order.OrderStatus status) {
        return new OrderSummaryResponse(
                id,
                "ORD-ABCDEF12",
                1L,
                "Ada Lovelace",
                10L,
                "Cafe Aroma",
                status,
                215.0,
                "Ring doorbell",
                LocalDateTime.of(2026, 8, 14, 12, 30),
                null);
    }

    private Order detailedOrder(Long id, Order.OrderStatus status) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNumber("ORD-ABCDEF12");
        order.setCustomer(customer(1L, "Ada Lovelace"));
        order.setRestaurant(restaurant(10L, "Cafe Aroma"));
        order.setDeliveryAddress(address(20L, customer(1L, "Ada Lovelace")));
        order.setStatus(status);
        order.setSubtotal(200.0);
        order.setDeliveryFee(20.0);
        order.setTaxAmount(10.0);
        order.setDiscountAmount(15.0);
        order.setTotalAmount(215.0);
        order.setCreatedAt(LocalDateTime.of(2026, 8, 14, 12, 30));
        order.setSpecialInstructions("Ring doorbell");
        return order;
    }
}
