package com.bhukkad.commerce.order.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.commerce.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.commerce.order.api.dto.request.OrderItemRequest;
import com.bhukkad.commerce.order.api.dto.response.OrderResponse;
import com.bhukkad.commerce.order.domain.entity.CartItem;
import com.bhukkad.commerce.order.domain.repository.OrderRepository;
import com.bhukkad.commerce.order.domain.service.impl.AsyncOrderCreateService;
import com.bhukkad.commerce.order.domain.service.impl.CartService;
import com.bhukkad.commerce.order.domain.service.impl.OrderCreateIdempotencyService;
import com.bhukkad.commerce.order.domain.service.impl.OrderCreateJobService;
import com.bhukkad.commerce.order.domain.service.impl.OrderService;
import com.bhukkad.commerce.order.infrastructure.client.RestaurantClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cart checkout: {@code POST /api/v1/orders/customer/create} with the item list
 * omitted must check out the customer's ACTIVE CART.
 *
 * <p>Regression cover for the {@code @NotEmpty} that used to sit on
 * {@code CreateOrderRequest.items}: bean validation rejected the request with
 * 400 "Order must contain at least one item" before the controller could read
 * the cart, so the documented cart-checkout path was unreachable and every
 * client that omitted {@code items} got a 400. The invariant now lives in the
 * controller, after the cart has been resolved.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LegacyOrderCompatCartCheckoutTest {

    @Mock private OrderService orderService;
    @Mock private CartService cartService;
    @Mock private OrderRepository orderRepository;
    @Mock private RestaurantClient restaurantClient;
    @Mock private OrderCreateJobService orderCreateJobService;
    @Mock private AsyncOrderCreateService asyncOrderCreateService;
    @Mock private OrderCreateIdempotencyService idempotencyService;

    private LegacyOrderCompatController controller() {
        return new LegacyOrderCompatController(orderService, cartService, orderRepository,
                restaurantClient, orderCreateJobService, asyncOrderCreateService, idempotencyService);
    }

    private static final TokenPrincipal CUSTOMER = new TokenPrincipal(7L, "c@bhukkad.dev", "CUSTOMER");

    private static OrderResponse storedOrder() {
        return new OrderResponse(42L, 7L, 100L, "PLACED", new BigDecimal("320.00"), List.of());
    }

    @Test
    void omittedItems_checksOutActiveCart() {
        CartItem ci = new CartItem();
        ci.setMenuItemId(42L);
        ci.setItemName("Butter Chicken");
        ci.setUnitPrice(new BigDecimal("320.00"));
        ci.setQuantity(2);
        when(cartService.getItems(7L)).thenReturn(List.of(ci));
        when(orderService.createOrder(any())).thenReturn(storedOrder());

        controller().create(CUSTOMER, null, new CreateOrderRequest(null, 100L, null));

        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderService).createOrder(captor.capture());
        CreateOrderRequest scoped = captor.getValue();
        assertThat(scoped.customerId()).isEqualTo(7L);
        assertThat(scoped.items()).hasSize(1);
        assertThat(scoped.items().getFirst().menuItemId()).isEqualTo(42L);
        assertThat(scoped.items().getFirst().quantity()).isEqualTo(2);
        // Cart checkout is destructive: the cart must be cleared afterwards.
        verify(cartService).clear(7L);
    }

    @Test
    void omittedItems_emptyCart_failsWithBusinessError() {
        when(cartService.getItems(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> controller().create(CUSTOMER, null, new CreateOrderRequest(null, 100L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one item");

        verify(orderService, org.mockito.Mockito.never()).createOrder(any());
    }

    @Test
    void explicitItems_doNotReadTheCart() {
        when(orderService.createOrder(any())).thenReturn(storedOrder());

        controller().create(CUSTOMER, null, new CreateOrderRequest(null, 100L, List.of(
                new OrderItemRequest(42L, "Butter Chicken", new BigDecimal("320.00"), 1))));

        verify(cartService, org.mockito.Mockito.never()).getItems(org.mockito.ArgumentMatchers.anyLong());
    }
}
