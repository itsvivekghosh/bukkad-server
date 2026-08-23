package com.bhukkad.serviceImpl;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.dto.request.BatchOrderRequest;
import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.BatchOrderResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.*;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.idempotency.OrderIdempotencyService;
import com.bhukkad.inventory.StockReservationService;
import com.bhukkad.mapper.OrderMapper;
import com.bhukkad.metrics.BusinessMetrics;
import com.bhukkad.metrics.OrderMetrics;
import com.bhukkad.order.ScheduledOrderValidator;
import com.bhukkad.repository.*;
import com.bhukkad.restaurant.RestaurantBusyService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CouponService;
import com.bhukkad.service.OrderPricingService;
import com.bhukkad.service.PaymentService;
import com.bhukkad.timeline.OrderTimelineService;
import com.bhukkad.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderPlacementServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private RestaurantRepository restaurantRepository;
    @Mock private AddressRepository addressRepository;
    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;
    @Mock private MenuItemRepository menuItemRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private OrderCacheService orderCacheService;
    @Mock private OrderEventPublisher orderEventPublisher;
    @Mock private OrderPricingService orderPricingService;
    @Mock private CouponService couponService;
    @Mock private PaymentService paymentService;
    @Mock private OrderMapper orderMapper;
    @Mock private OrderIdempotencyService orderIdempotencyService;
    @Mock private OrderMetrics orderMetrics;
    @Mock private BusinessMetrics businessMetrics;
    @Mock private WalletService walletService;
    @Mock private ScheduledOrderValidator scheduledOrderValidator;
    @Mock private OrderEtaService orderEtaService;
    @Mock private StockReservationService stockReservationService;
    @Mock private OrderTimelineService orderTimelineService;
    @Mock private RestaurantBusyService restaurantBusyService;

    private OrderPlacementService service;

    private Customer customer;
    private Restaurant restaurant;
    private Cart cart;
    private CartItem cartItem;

    @BeforeEach
    void setUp() {
        service = new OrderPlacementService(
                orderRepository, customerRepository, restaurantRepository, addressRepository,
                cartRepository, cartItemRepository, menuItemRepository, securityUtils,
                orderCacheService, orderEventPublisher, orderPricingService, couponService,
                paymentService, orderMapper, orderIdempotencyService, orderMetrics,
                businessMetrics, walletService, scheduledOrderValidator, orderEtaService,
                stockReservationService, orderTimelineService, restaurantBusyService);

        Address address = new Address();
        address.setId(60L);
        address.setCustomer(newCustomer());
        address.setLatitude(12.9716);
        address.setLongitude(77.5946);

        customer = newCustomer();
        customer.setLoyaltyPoints(500);

        restaurant = new Restaurant();
        restaurant.setId(10L);
        restaurant.setIsActive(true);
        restaurant.setIsOpen(true);
        restaurant.setAverageDeliveryTime(30);
        restaurant.setDeliveryFee(50.0);

        MenuCategory category = new MenuCategory();
        category.setId(20L);
        category.setRestaurant(restaurant);
        category.setName("Burgers");

        MenuItem menuItem = new MenuItem();
        menuItem.setId(30L);
        menuItem.setCategory(category);
        menuItem.setPrice(100.0);
        menuItem.setName("Test Burger");
        menuItem.setStockQuantity(10);
        menuItem.setAvailable(true);

        cartItem = new CartItem();
        cartItem.setId(40L);
        cartItem.setMenuItem(menuItem);
        cartItem.setQuantity(2);

        cart = new Cart();
        cart.setId(50L);
        cart.setCustomer(customer);
        cart.setRestaurant(restaurant);
    }

    private Customer newCustomer() {
        Customer c = new Customer();
        c.setId(1L);
        return c;
    }

    private Address newAddress() {
        Address a = new Address();
        a.setId(60L);
        a.setCustomer(customer);
        a.setLatitude(12.9716);
        a.setLongitude(77.5946);
        return a;
    }

    private OrderRequest buildOrderRequest() {
        OrderRequest request = new OrderRequest();
        request.setRestaurantId(10L);
        request.setDeliveryAddressId(60L);
        request.setPaymentMethod("CREDIT_CARD");
        request.setTipAmount(0.0);
        return request;
    }

    private OrderPricingService.OrderPricingResult buildPricingResult() {
        return new OrderPricingService.OrderPricingResult(
                200.0, 50.0, 10.0, 0.0, 0.0, 0, 0.0, 260.0, 260.0, null);
    }

    private void stubHappyPathPrerequisites(String idempotencyKey) {
        lenient().when(securityUtils.getCurrentUserId()).thenReturn(1L);
        lenient().when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        lenient().when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        lenient().when(scheduledOrderValidator.isScheduledOrder(null)).thenReturn(false);
        lenient().when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        lenient().when(cartItemRepository.findByCartIdWithMenuItem(cart.getId())).thenReturn(List.of(cartItem));
        lenient().when(addressRepository.findByIdWithCustomer(60L)).thenReturn(Optional.of(newAddress()));
        lenient().when(orderPricingService.calculate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(buildPricingResult());
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(100L);
            return o;
        });
        lenient().when(orderMapper.toResponse(any(Order.class))).thenAnswer(inv ->
                OrderResponse.builder().id(((Order) inv.getArgument(0)).getId()).build());
        lenient().when(paymentService.createPayment(anyLong(), anyString(), any()))
                .thenReturn(paymentWithId());
        lenient().when(paymentService.processPayment(anyLong(), any())).thenReturn(paymentWithId());
    }

    private Payment paymentWithId() {
        Payment p = new Payment();
        p.setId(200L);
        p.setPaymentMethod(Payment.PaymentMethod.CREDIT_CARD);
        return p;
    }

    @Test
    void createOrder_replaysCompletedIdempotencyResponse() {
        var request = new OrderRequest();
        request.setRestaurantId(1L);

        var cached = Optional.of(OrderResponse.builder().id(99L).build());
        when(orderIdempotencyService.findCompletedResponse("key-1")).thenReturn(cached);

        var response = service.createOrder(request, "key-1");
        assertEquals(99L, response.getId());
        verify(orderIdempotencyService).findCompletedResponse("key-1");
    }

    @Test
    void createOrder_marksIdempotencyFailed_whenCreationThrows() {
        var request = new OrderRequest();
        request.setRestaurantId(1L);

        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(orderIdempotencyService.findCompletedResponse("key-2")).thenReturn(Optional.empty());
        when(customerRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.createOrder(request, "key-2"));
        verify(orderIdempotencyService).failOrderCreate("key-2");
    }

    @Test
    void createBatchOrders_emptyCart_throws() {
        var request = new BatchOrderRequest();
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(cartRepository.findByCustomerIdWithRestaurant(7L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class,
                () -> service.createBatchOrders(request, null));
    }

    @Test
    void createBatchOrders_emptyCartItems_throws() {
        var request = new BatchOrderRequest();
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(cartRepository.findByCustomerIdWithRestaurant(7L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(cart.getId())).thenReturn(List.of());

        assertThrows(BusinessException.class,
                () -> service.createBatchOrders(request, null));
    }

    @Test
    void createBatchOrders_createsOrdersForAllRestaurants() {
        BatchOrderRequest request = new BatchOrderRequest();
        request.setDeliveryAddressId(60L);
        request.setPaymentMethod("CREDIT_CARD");
        request.setTipAmount(20.0);

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(cart.getId())).thenReturn(List.of(cartItem));
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(scheduledOrderValidator.isScheduledOrder(null)).thenReturn(false);
        when(addressRepository.findByIdWithCustomer(60L)).thenReturn(Optional.of(newAddress()));
        when(orderPricingService.calculate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(buildPricingResult());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(100L);
            return o;
        });
        when(orderMapper.toResponse(any(Order.class))).thenAnswer(inv ->
                OrderResponse.builder().id(((Order) inv.getArgument(0)).getId()).build());
        when(paymentService.createPayment(anyLong(), anyString(), any())).thenReturn(paymentWithId());
        when(paymentService.processPayment(anyLong(), any())).thenReturn(paymentWithId());

        BatchOrderResponse response = service.createBatchOrders(request, null);

        assertEquals(1, response.getSuccessCount());
        assertEquals(0, response.getFailureCount());
        assertEquals(1, response.getOrders().size());
    }

    @Test
    void createBatchOrders_recordsFailureForSubOrder() {
        BatchOrderRequest request = new BatchOrderRequest();
        request.setDeliveryAddressId(60L);
        request.setPaymentMethod("CREDIT_CARD");

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(cart.getId())).thenReturn(List.of(cartItem));
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        BatchOrderResponse response = service.createBatchOrders(request, null);

        assertEquals(0, response.getSuccessCount());
        assertEquals(1, response.getFailureCount());
        assertEquals(1, response.getErrors().size());
    }

    @Test
    void createOrder_successfulOrderCreation() {
        OrderRequest request = buildOrderRequest();
        stubHappyPathPrerequisites(null);

        OrderResponse response = service.createOrder(request, null);

        assertEquals(100L, response.getId());
        verify(orderIdempotencyService).completeOrderCreate(null, response);
    }

    @Test
    void createOrder_throwsWhenRestaurantInactive() {
        OrderRequest request = buildOrderRequest();
        restaurant.setIsActive(false);

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));

        assertThrows(BusinessException.class, () -> service.createOrder(request, null));
    }

    @Test
    void createOrder_throwsWhenRestaurantClosed() {
        OrderRequest request = buildOrderRequest();
        restaurant.setIsOpen(false);

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));

        assertThrows(BusinessException.class, () -> service.createOrder(request, null));
    }

    @Test
    void createOrder_throwsWhenCartEmpty() {
        OrderRequest request = buildOrderRequest();

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.createOrder(request, null));
    }

    @Test
    void createOrder_throwsWhenNoCartItemsForRestaurant() {
        OrderRequest request = buildOrderRequest();

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(cart.getId())).thenReturn(List.of());

        assertThrows(BusinessException.class, () -> service.createOrder(request, null));
    }

    @Test
    void createOrder_throwsWhenAddressNotFound() {
        OrderRequest request = buildOrderRequest();

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(cart.getId())).thenReturn(List.of(cartItem));
        when(addressRepository.findByIdWithCustomer(60L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.createOrder(request, null));
    }

    @Test
    void createOrder_throwsWhenAddressNotBelongingToCustomer() {
        OrderRequest request = buildOrderRequest();
        Customer otherCustomer = new Customer();
        otherCustomer.setId(99L);
        Address otherAddress = new Address();
        otherAddress.setId(60L);
        otherAddress.setCustomer(otherCustomer);

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(cart.getId())).thenReturn(List.of(cartItem));
        when(addressRepository.findByIdWithCustomer(60L)).thenReturn(Optional.of(otherAddress));

        assertThrows(BusinessException.class, () -> service.createOrder(request, null));
    }

    @Test
    void createOrder_withScheduledOrder() {
        OrderRequest request = buildOrderRequest();
        request.setScheduledAt(LocalDateTime.now().plusHours(2));

        stubHappyPathPrerequisites(null);
        when(scheduledOrderValidator.isScheduledOrder(request.getScheduledAt())).thenReturn(true);

        OrderResponse response = service.createOrder(request, null);
        assertEquals(100L, response.getId());
    }

    @Test
    void createOrder_withWalletPayment_debitsWallet() {
        OrderRequest request = buildOrderRequest();
        request.setPaymentMethod("WALLET");

        OrderPricingService.OrderPricingResult pricing = new OrderPricingService.OrderPricingResult(
                200.0, 50.0, 10.0, 0.0, 0.0, 0, 50.0, 260.0, 260.0, null);

        stubHappyPathPrerequisites(null);
        when(orderPricingService.calculate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pricing);

        OrderResponse response = service.createOrder(request, null);
        assertEquals(100L, response.getId());
        verify(walletService).debit(eq(customer), eq(50.0), any(), any(), anyString());
        verify(customerRepository, never()).save(customer);
    }

    @Test
    void createOrder_withLoyaltyPoints_redeemsLoyalty() {
        OrderRequest request = buildOrderRequest();

        OrderPricingService.OrderPricingResult pricing = new OrderPricingService.OrderPricingResult(
                200.0, 50.0, 10.0, 0.0, 25.0, 100, 0.0, 235.0, 235.0, null);

        stubHappyPathPrerequisites(null);
        when(orderPricingService.calculate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pricing);

        OrderResponse response = service.createOrder(request, null);
        assertEquals(100L, response.getId());
        assertEquals(400, customer.getLoyaltyPoints());
        verify(customerRepository).save(customer);
    }

    @Test
    void createOrder_withAppliedCoupon_recordsCouponUsage() {
        OrderRequest request = buildOrderRequest();
        Coupon coupon = new Coupon();
        coupon.setId(1L);

        OrderPricingService.OrderPricingResult pricing = new OrderPricingService.OrderPricingResult(
                200.0, 50.0, 10.0, 50.0, 0.0, 0, 0.0, 210.0, 260.0, coupon);

        stubHappyPathPrerequisites(null);
        when(orderPricingService.calculate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(pricing);

        OrderResponse response = service.createOrder(request, null);
        assertEquals(100L, response.getId());
        verify(couponService).recordCouponUsage(eq(coupon), eq(1L), eq(100L));
    }

    @Test
    void createOrder_cashOnDelivery_skipsProcessPayment() {
        OrderRequest request = buildOrderRequest();
        request.setPaymentMethod("COD");

        Payment cashPayment = new Payment();
        cashPayment.setPaymentMethod(Payment.PaymentMethod.CASH_ON_DELIVERY);

        stubHappyPathPrerequisites(null);
        when(paymentService.createPayment(anyLong(), eq("CASH_ON_DELIVERY"), any()))
                .thenReturn(cashPayment);

        OrderResponse response = service.createOrder(request, null);
        assertEquals(100L, response.getId());
        verify(paymentService).createPayment(anyLong(), eq("CASH_ON_DELIVERY"), any());
        verify(paymentService, never()).processPayment(anyLong(), any());
    }

    @Test
    void createOrder_throwsWhenPaymentMethodInvalid() {
        OrderRequest request = buildOrderRequest();
        request.setPaymentMethod("BITCOIN");

        stubHappyPathPrerequisites(null);

        assertThrows(BusinessException.class, () -> service.createOrder(request, null));
    }

    @Test
    void createOrder_throwsWhenPaymentMethodNull() {
        OrderRequest request = buildOrderRequest();
        request.setPaymentMethod(null);

        stubHappyPathPrerequisites(null);

        assertThrows(BusinessException.class, () -> service.createOrder(request, null));
    }

    @Test
    void createOrder_releasesStockOnFailure() {
        OrderRequest request = buildOrderRequest();

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(cart.getId())).thenReturn(List.of(cartItem));
        when(addressRepository.findByIdWithCustomer(60L)).thenReturn(Optional.of(newAddress()));
        when(orderPricingService.calculate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(buildPricingResult());
        when(stockReservationService.isEnabled()).thenReturn(true);
        when(orderRepository.save(any(Order.class)))
                .thenThrow(new RuntimeException("DB error"));

        assertThrows(RuntimeException.class, () -> service.createOrder(request, null));
        verify(stockReservationService).releaseStock(anyList());
    }

    @Test
    void createOrder_swrapsOptimisticLockAsBusinessException() {
        OrderRequest request = buildOrderRequest();

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(cart.getId())).thenReturn(List.of(cartItem));
        when(addressRepository.findByIdWithCustomer(60L)).thenReturn(Optional.of(newAddress()));
        when(orderPricingService.calculate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(buildPricingResult());
        when(orderRepository.save(any(Order.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException("Order", 1L,
                        "conflict", new RuntimeException()));

        assertThrows(BusinessException.class, () -> service.createOrder(request, null));
    }

    @Test
    void createOrder_decrementsStockForItemsWithStock() {
        OrderRequest request = buildOrderRequest();
        stubHappyPathPrerequisites(null);

        OrderResponse response = service.createOrder(request, null);
        assertEquals(100L, response.getId());
        verify(menuItemRepository).saveAll(anyList());
        verify(stockReservationService).syncStock(any(MenuItem.class));
    }

    @Test
    void createOrder_handlesInsufficientStock() {
        OrderRequest request = buildOrderRequest();
        cartItem.setQuantity(15);
        cartItem.getMenuItem().setStockQuantity(10);

        stubHappyPathPrerequisites(null);

        assertThrows(BusinessException.class, () -> service.createOrder(request, null));
    }

    @Test
    void createOrder_handlesNullTipAmount() {
        OrderRequest request = buildOrderRequest();
        request.setTipAmount(null);

        stubHappyPathPrerequisites(null);

        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            assertEquals(0.0, o.getTipAmount());
            o.setId(100L);
            return o;
        });

        OrderResponse response = service.createOrder(request, null);
        assertEquals(100L, response.getId());
    }

    @Test
    void createOrder_resolvesCodPaymentMethodToCashOnDelivery() {
        OrderRequest request = buildOrderRequest();
        request.setPaymentMethod("cod");

        Payment cashPayment = new Payment();
        cashPayment.setPaymentMethod(Payment.PaymentMethod.CASH_ON_DELIVERY);

        stubHappyPathPrerequisites(null);
        when(paymentService.createPayment(anyLong(), eq("CASH_ON_DELIVERY"), any()))
                .thenReturn(cashPayment);

        OrderResponse response = service.createOrder(request, null);
        assertEquals(100L, response.getId());
        verify(paymentService).createPayment(anyLong(), eq("CASH_ON_DELIVERY"), any());
    }

    @Test
    void createOrder_usesOrderNumberPrefix_andPublishesEvents() {
        OrderRequest request = buildOrderRequest();
        stubHappyPathPrerequisites(null);

        OrderResponse response = service.createOrder(request, null);
        assertEquals(100L, response.getId());

        verify(orderEventPublisher).publishCreated(any(Order.class));
        verify(orderMetrics).orderCreated();
        verify(businessMetrics).checkout();
        verify(businessMetrics).payment();
        verify(orderCacheService).invalidateOrder(eq(100L), eq(1L), eq(10L));
    }

    @Test
    void createOrder_withIdempotencyKey_completesIdempotency() {
        OrderRequest request = buildOrderRequest();
        stubHappyPathPrerequisites("idem-key");

        OrderResponse response = service.createOrder(request, "idem-key");
        assertEquals(100L, response.getId());
        verify(orderIdempotencyService).beginOrderCreate("idem-key", 1L);
        verify(orderIdempotencyService).completeOrderCreate(eq("idem-key"), eq(response));
    }

    @Test
    void createOrder_idempotencyKeyNull_noBeginCall() {
        OrderRequest request = buildOrderRequest();
        stubHappyPathPrerequisites(null);

        service.createOrder(request, null);
        verify(orderIdempotencyService, never()).beginOrderCreate(anyString(), anyLong());
    }
}
