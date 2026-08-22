package com.bhukkad.serviceImpl;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.event.OrderEventPublisher;
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
import com.bhukkad.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@link OrderPlacementService}, extracted from the
 * original {@code OrderServiceImpl} during the god-class split. These verify
 * the idempotency contract of order creation.
 */
@ExtendWith(MockitoExtension.class)
class OrderPlacementServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private CartRepository cartRepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private MenuItemRepository menuItemRepository;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private OrderCacheService orderCacheService;
    @Mock
    private OrderEventPublisher orderEventPublisher;
    @Mock
    private OrderPricingService orderPricingService;
    @Mock
    private CouponService couponService;
    @Mock
    private PaymentService paymentService;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderIdempotencyService orderIdempotencyService;
    @Mock
    private OrderMetrics orderMetrics;
    @Mock
    private BusinessMetrics businessMetrics;
    @Mock
    private WalletService walletService;
    @Mock
    private ScheduledOrderValidator scheduledOrderValidator;
    @Mock
    private OrderEtaService orderEtaService;
    @Mock
    private StockReservationService stockReservationService;
    @Mock
    private OrderTimelineService orderTimelineService;
    @Mock
    private RestaurantBusyService restaurantBusyService;

    private OrderPlacementService service;

    @BeforeEach
    void setUp() {
        service = new OrderPlacementService(
                orderRepository, customerRepository, restaurantRepository, addressRepository,
                cartRepository, cartItemRepository, menuItemRepository, securityUtils,
                orderCacheService, orderEventPublisher, orderPricingService, couponService,
                paymentService, orderMapper, orderIdempotencyService, orderMetrics,
                businessMetrics, walletService, scheduledOrderValidator, orderEtaService,
                stockReservationService, orderTimelineService, restaurantBusyService);
    }

    @Test
    void createOrder_replaysCompletedIdempotencyResponse() {
        var request = new com.bhukkad.dto.request.OrderRequest();
        request.setRestaurantId(1L);

        var cached = java.util.Optional.of(
                com.bhukkad.dto.response.OrderResponse.builder().id(99L).build());
        when(orderIdempotencyService.findCompletedResponse("key-1")).thenReturn(cached);

        var response = service.createOrder(request, "key-1");

        assert response.getId() == 99L : "expected cached response to be replayed";
        verify(orderIdempotencyService).findCompletedResponse("key-1");
    }

    @Test
    void createOrder_marksIdempotencyFailed_whenCreationThrows() {
        var request = new com.bhukkad.dto.request.OrderRequest();
        request.setRestaurantId(1L);

        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(orderIdempotencyService.findCompletedResponse("key-2"))
                .thenReturn(java.util.Optional.empty());
        when(customerRepository.findById(7L))
                .thenReturn(java.util.Optional.empty());

        assertThrows(com.bhukkad.exception.ResourceNotFoundException.class,
                () -> service.createOrder(request, "key-2"));
        verify(orderIdempotencyService).failOrderCreate("key-2");
    }

    @Test
    void createBatchOrders_emptyCart_throws() {
        var request = new com.bhukkad.dto.request.BatchOrderRequest();
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(cartRepository.findByCustomerIdWithRestaurant(7L))
                .thenReturn(java.util.Optional.empty());

        assertThrows(com.bhukkad.exception.BusinessException.class,
                () -> service.createBatchOrders(request, null));
    }
}
