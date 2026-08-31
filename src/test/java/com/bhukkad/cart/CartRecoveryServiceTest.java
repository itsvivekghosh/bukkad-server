package com.bhukkad.cart;

import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.entity.Cart;
import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.Coupon;
import com.bhukkad.entity.Customer;
import com.bhukkad.notification.push.PushNotificationSender;
import com.bhukkad.repository.CartItemRepository;
import com.bhukkad.repository.CartRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.serviceImpl.CouponServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartRecoveryServiceTest {

    @Mock
    private CartRepository cartRepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CouponServiceImpl couponService;
    @Mock
    private PushNotificationSender pushNotificationSender;
    @Mock
    private RedisCacheService redisCacheService;

    private CartRecoveryProperties properties;
    private CartRecoveryService service;

    @BeforeEach
    void setUp() {
        properties = new CartRecoveryProperties();
        properties.setEnabled(true);
        service = new CartRecoveryService(cartRepository, cartItemRepository, orderRepository,
                couponService, pushNotificationSender, redisCacheService, properties);
    }

    private Cart idleCart(Long cartId, Long customerId, LocalDateTime updatedAt) {
        Cart cart = new Cart();
        cart.setId(cartId);
        Customer customer = new Customer();
        customer.setId(customerId);
        cart.setCustomer(customer);
        cart.setUpdatedAt(updatedAt);
        return cart;
    }

    private Coupon coupon(String code) {
        Coupon coupon = new Coupon();
        coupon.setCode(code);
        return coupon;
    }

    @Test
    void recoverAbandonedCarts_disabled_doesNothing() {
        properties.setEnabled(false);
        service.recoverAbandonedCarts();
        verifyNoInteractions(cartRepository, cartItemRepository, orderRepository,
                couponService, pushNotificationSender, redisCacheService);
    }

    @Test
    void recoverAbandonedCarts_idleCartWithItemsAndNoOrder_createsCouponNotifiesAndMarksProcessed() {
        Cart cart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        when(cartRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(cart)));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(new CartItem()));
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(7L, cart.getUpdatedAt())).thenReturn(0L);
        when(redisCacheService.exists("cart-recovery:5")).thenReturn(false);
        when(couponService.createRecoveryCoupon(anyString(), anyString(), eq(10.0), eq(200.0), eq(3)))
                .thenReturn(coupon("BACK-5-ABC123"));

        service.recoverAbandonedCarts();

        verify(couponService).createRecoveryCoupon(anyString(), anyString(), eq(10.0), eq(200.0), eq(3));
        verify(pushNotificationSender).sendToUser(eq(7L), anyString(), contains("BACK-5-ABC123"));
        verify(redisCacheService).set(eq("cart-recovery:5"), eq(Boolean.TRUE), eq(3L * 24 * 3600));
    }

    @Test
    void recoverAbandonedCarts_alreadyProcessed_skipsCart() {
        Cart cart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        when(cartRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(cart)));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(new CartItem()));
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(7L, cart.getUpdatedAt())).thenReturn(0L);
        when(redisCacheService.exists("cart-recovery:5")).thenReturn(true);

        service.recoverAbandonedCarts();

        verify(couponService, never()).createRecoveryCoupon(anyString(), anyString(),
                eq(10.0), eq(200.0), eq(3));
        verify(pushNotificationSender, never()).sendToUser(eq(7L), anyString(), anyString());
    }

    @Test
    void recoverAbandonedCarts_recentlyUpdatedCart_skipped() {
        Cart cart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(10));
        when(cartRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(cart)));

        service.recoverAbandonedCarts();

        verify(cartItemRepository, never()).findByCartId(5L);
        verify(couponService, never()).createRecoveryCoupon(anyString(), anyString(),
                eq(10.0), eq(200.0), eq(3));
    }

    @Test
    void recoverAbandonedCarts_emptyCart_skipped() {
        Cart cart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        when(cartRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(cart)));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of());

        service.recoverAbandonedCarts();

        verify(orderRepository, never()).countByCustomerIdAndCreatedAtAfter(7L, cart.getUpdatedAt());
        verify(couponService, never()).createRecoveryCoupon(anyString(), anyString(),
                eq(10.0), eq(200.0), eq(3));
    }

    @Test
    void recoverAbandonedCarts_cartWithOrderSince_skipped() {
        Cart cart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        when(cartRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(cart)));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(new CartItem()));
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(7L, cart.getUpdatedAt())).thenReturn(1L);

        service.recoverAbandonedCarts();

        verify(couponService, never()).createRecoveryCoupon(anyString(), anyString(),
                eq(10.0), eq(200.0), eq(3));
    }

    @Test
    void recoverAbandonedCarts_couponCreationFailure_logsWarnWithoutNotifyingOrMarking() {
        Cart cart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        when(cartRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(cart)));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(new CartItem()));
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(7L, cart.getUpdatedAt())).thenReturn(0L);
        when(redisCacheService.exists("cart-recovery:5")).thenReturn(false);
        when(couponService.createRecoveryCoupon(anyString(), anyString(), eq(10.0), eq(200.0), eq(3)))
                .thenThrow(new RuntimeException("coupon code already exists"));

        assertDoesNotThrow(() -> service.recoverAbandonedCarts());

        verify(pushNotificationSender, never()).sendToUser(eq(7L), anyString(), anyString());
        verify(redisCacheService, never()).set(eq("cart-recovery:5"), eq(Boolean.TRUE), eq(3L * 24 * 3600));
    }

    @Test
    void recoverAbandonedCarts_notificationFailure_doesNotBreakSweep() {
        Cart cart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        when(cartRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(cart)));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(new CartItem()));
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(7L, cart.getUpdatedAt())).thenReturn(0L);
        when(redisCacheService.exists("cart-recovery:5")).thenReturn(false);
        when(couponService.createRecoveryCoupon(anyString(), anyString(), eq(10.0), eq(200.0), eq(3)))
                .thenReturn(coupon("BACK-5-ABC123"));
        org.mockito.Mockito.doThrow(new RuntimeException("push provider down"))
                .when(pushNotificationSender).sendToUser(eq(7L), anyString(), anyString());

        assertDoesNotThrow(() -> service.recoverAbandonedCarts());

        verify(redisCacheService).set(eq("cart-recovery:5"), eq(Boolean.TRUE), eq(3L * 24 * 3600));
    }

    @Test
    void recoverAbandonedCarts_oneFailingCartDoesNotStopOthers() {
        Cart okCart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        Cart failingCart = idleCart(6L, 8L, LocalDateTime.now().minusMinutes(90));
        when(cartRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(okCart, failingCart)));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(new CartItem()));
        when(cartItemRepository.findByCartId(6L)).thenReturn(List.of(new CartItem()));
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(7L, okCart.getUpdatedAt())).thenReturn(0L);
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(8L, failingCart.getUpdatedAt())).thenReturn(0L);
        when(redisCacheService.exists("cart-recovery:5")).thenReturn(false);
        when(redisCacheService.exists("cart-recovery:6")).thenReturn(false);
        when(couponService.createRecoveryCoupon(anyString(), anyString(), eq(10.0), eq(200.0), eq(3)))
                .thenReturn(coupon("BACK-5-OK123"))
                .thenThrow(new RuntimeException("duplicate code"));

        assertDoesNotThrow(() -> service.recoverAbandonedCarts());

        verify(pushNotificationSender).sendToUser(eq(7L), anyString(), contains("BACK-5-OK123"));
        verify(redisCacheService).set(eq("cart-recovery:5"), eq(Boolean.TRUE), eq(3L * 24 * 3600));
        verify(redisCacheService, never()).set(eq("cart-recovery:6"), eq(Boolean.TRUE), eq(3L * 24 * 3600));
    }

    @Test
    void recoverAbandonedCarts_multipageSweep_sleepsBetweenBatches() {
        // Batch A pagination: two batches; the inter-batch sleep path must not break the sweep.
        Cart qualifying = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        org.springframework.data.domain.Page<Cart> page1 =
                new org.springframework.data.domain.PageImpl<>(List.of(qualifying),
                        org.springframework.data.domain.PageRequest.of(0, 1), 2);
        org.springframework.data.domain.Page<Cart> page2 =
                new org.springframework.data.domain.PageImpl<>(List.of(),
                        org.springframework.data.domain.PageRequest.of(1, 1), 2);
        when(cartRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page1).thenReturn(page2);
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(new CartItem()));
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(7L, qualifying.getUpdatedAt())).thenReturn(0L);
        when(redisCacheService.exists("cart-recovery:5")).thenReturn(false);
        when(couponService.createRecoveryCoupon(anyString(), anyString(), eq(10.0), eq(200.0), eq(3)))
                .thenReturn(coupon("BACK-5-MULTI"));

        service.recoverAbandonedCarts();

        verify(cartRepository, times(2)).findAll(any(org.springframework.data.domain.Pageable.class));
        verify(pushNotificationSender).sendToUser(eq(7L), anyString(), contains("BACK-5-MULTI"));
    }

    @Test
    void findIdleCarts_appliesAllFiltersAcrossPages() {
        LocalDateTime old = LocalDateTime.now().minusMinutes(60);
        LocalDateTime recent = LocalDateTime.now().minusMinutes(1);

        Cart idleWithItems = idleCart(1L, 7L, old);            // qualifies
        Cart recentlyUpdated = idleCart(2L, 8L, recent);       // skipped: not idle
        Cart nullUpdated = idleCart(3L, 9L, null);             // skipped: null updatedAt
        Cart emptyCart = idleCart(4L, 10L, old);               // skipped: no items
        Cart orderSinceCart = idleCart(5L, 11L, old);          // skipped: ordered since
        Cart idleWithItems2 = idleCart(6L, 12L, old);          // qualifies (page 2)

        org.springframework.data.domain.Page<Cart> page1 =
                new org.springframework.data.domain.PageImpl<>(
                        List.of(idleWithItems, recentlyUpdated, nullUpdated),
                        org.springframework.data.domain.PageRequest.of(0, 3), 6);
        org.springframework.data.domain.Page<Cart> page2 =
                new org.springframework.data.domain.PageImpl<>(
                        List.of(emptyCart, orderSinceCart, idleWithItems2),
                        org.springframework.data.domain.PageRequest.of(1, 3), 6);
        when(cartRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page1).thenReturn(page2);
        when(cartItemRepository.findByCartId(1L)).thenReturn(List.of(new CartItem()));
        when(cartItemRepository.findByCartId(4L)).thenReturn(List.of());
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(new CartItem()));
        when(cartItemRepository.findByCartId(6L)).thenReturn(List.of(new CartItem()));
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(7L, old)).thenReturn(0L);
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(11L, old)).thenReturn(1L);
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(12L, old)).thenReturn(0L);

        java.util.List<Cart> result = service.findIdleCarts();

        // Only the two genuinely-abandoned carts survive the filters.
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals(6L, result.get(1).getId());
    }

    @Test
    void recoverAbandonedCarts_fractionalCouponPercent_formatsDecimals() {
        // formatPercent's non-integer branch: 12.5 must render as "12.5", not "12"
        properties.setCouponPercent(12.5);
        Cart cart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        when(cartRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(cart)));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(new CartItem()));
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(7L, cart.getUpdatedAt())).thenReturn(0L);
        when(redisCacheService.exists("cart-recovery:5")).thenReturn(false);
        when(couponService.createRecoveryCoupon(anyString(), anyString(), eq(12.5), eq(200.0), eq(3)))
                .thenReturn(coupon("BACK-5-FRAC12"));

        service.recoverAbandonedCarts();

        verify(couponService).createRecoveryCoupon(anyString(), anyString(), eq(12.5), eq(200.0), eq(3));
        verify(pushNotificationSender).sendToUser(eq(7L), anyString(), contains("12.5% off"));
    }
}
