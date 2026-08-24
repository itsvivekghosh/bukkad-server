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

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
        when(cartRepository.findAll()).thenReturn(List.of(cart));
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
        when(cartRepository.findAll()).thenReturn(List.of(cart));
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
        when(cartRepository.findAll()).thenReturn(List.of(cart));

        service.recoverAbandonedCarts();

        verify(cartItemRepository, never()).findByCartId(5L);
        verify(couponService, never()).createRecoveryCoupon(anyString(), anyString(),
                eq(10.0), eq(200.0), eq(3));
    }

    @Test
    void recoverAbandonedCarts_emptyCart_skipped() {
        Cart cart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        when(cartRepository.findAll()).thenReturn(List.of(cart));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of());

        service.recoverAbandonedCarts();

        verify(orderRepository, never()).countByCustomerIdAndCreatedAtAfter(7L, cart.getUpdatedAt());
        verify(couponService, never()).createRecoveryCoupon(anyString(), anyString(),
                eq(10.0), eq(200.0), eq(3));
    }

    @Test
    void recoverAbandonedCarts_cartWithOrderSince_skipped() {
        Cart cart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        when(cartRepository.findAll()).thenReturn(List.of(cart));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(new CartItem()));
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(7L, cart.getUpdatedAt())).thenReturn(1L);

        service.recoverAbandonedCarts();

        verify(couponService, never()).createRecoveryCoupon(anyString(), anyString(),
                eq(10.0), eq(200.0), eq(3));
    }

    @Test
    void recoverAbandonedCarts_couponCreationFailure_logsWarnWithoutNotifyingOrMarking() {
        Cart cart = idleCart(5L, 7L, LocalDateTime.now().minusMinutes(60));
        when(cartRepository.findAll()).thenReturn(List.of(cart));
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
        when(cartRepository.findAll()).thenReturn(List.of(cart));
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
        when(cartRepository.findAll()).thenReturn(List.of(okCart, failingCart));
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
}
