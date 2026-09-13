package com.bhukkad.order.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.order.api.dto.request.OrderItemRequest;
import com.bhukkad.order.api.dto.response.OrderResponse;
import com.bhukkad.order.domain.repository.OrderRepository;
import com.bhukkad.order.domain.service.impl.AsyncOrderCreateService;
import com.bhukkad.order.domain.service.impl.CartService;
import com.bhukkad.order.domain.service.impl.OrderCreateIdempotencyService;
import com.bhukkad.order.domain.service.impl.OrderCreateJobService;
import com.bhukkad.order.domain.service.impl.OrderService;
import com.bhukkad.order.infrastructure.client.RestaurantClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BUG-B: the legacy sync create ignored {@code Idempotency-Key}, so a network
 * retry re-ran the whole saga (and 400'd once the first call had emptied the
 * cart). Same (scope,key)+same payload replays the stored order; a different
 * payload for a used key 409s; no key keeps the historical behavior verbatim.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LegacyOrderCompatIdempotentCreateTest {

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

    private static final TokenPrincipal CUSTOMER =
            new TokenPrincipal(7L, "c@bhukkad.dev", "CUSTOMER");

    private static CreateOrderRequest explicitOrder() {
        return new CreateOrderRequest(null, 100L, List.of(
                new OrderItemRequest(42L, "Butter Chicken", new BigDecimal("320.00"), 1)));
    }

    private static OrderResponse storedOrder() {
        return new OrderResponse(42L, 7L, 100L, "PLACED", new BigDecimal("320.00"), List.of());
    }

    @Test
    void freshKey_claimsRunsSagaOnce_andStoresResponse() {
        CreateOrderRequest request = explicitOrder();
        OrderResponse created = storedOrder();
        when(idempotencyService.claim(eq(7L), eq("key-1"), any()))
                .thenReturn(OrderCreateIdempotencyService.Claim.ofFresh());
        when(orderService.createOrder(any())).thenReturn(created);

        ResponseEntity<OrderResponse> response = controller().create(CUSTOMER, "key-1", request);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(created);
        verify(idempotencyService).complete(7L, "key-1", request, 200, created);
        verifyNoInteractions(cartService);
    }

    @Test
    void replayKey_samePayload_returnsStoredOrder_withoutReRunningSaga() {
        OrderResponse stored = storedOrder();
        when(idempotencyService.claim(eq(7L), eq("key-1"), any()))
                .thenReturn(OrderCreateIdempotencyService.Claim.ofReplay(200, stored));

        ResponseEntity<OrderResponse> response =
                controller().create(CUSTOMER, "key-1", explicitOrder());

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(stored);
        // The whole point of the fix: the replay never re-reads (or empties)
        // the cart and never touches the order pipeline again.
        verifyNoInteractions(orderService, cartService);
        verify(idempotencyService, never()).complete(any(), any(), any(), anyInt(), any());
        verify(idempotencyService, never()).markFailed(any(), any());
    }

    @Test
    void differentPayloadForUsedKey_surfaces409Conflict() {
        when(idempotencyService.claim(eq(7L), eq("key-used"), any()))
                .thenThrow(new DuplicateRequestException(
                        "Idempotency-Key reused with a different request payload"));

        assertThatThrownBy(() -> controller().create(CUSTOMER, "key-used", explicitOrder()))
                .isInstanceOf(DuplicateRequestException.class);
        verifyNoInteractions(orderService, cartService);
    }

    @Test
    void noKey_behaviorUnchanged_noClaimNoStore() {
        OrderResponse created = storedOrder();
        when(orderService.createOrder(any())).thenReturn(created);

        ResponseEntity<OrderResponse> response = controller().create(CUSTOMER, null, explicitOrder());

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(created);
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void blankKey_treatedAsNoKey() {
        OrderResponse created = storedOrder();
        when(orderService.createOrder(any())).thenReturn(created);

        ResponseEntity<OrderResponse> response = controller().create(CUSTOMER, "   ", explicitOrder());

        assertThat(response.getBody()).isSameAs(created);
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void cartCheckout_sagaFailure_marksClaimFailed_andPropagates() {
        when(idempotencyService.claim(eq(7L), eq("key-1"), any()))
                .thenReturn(OrderCreateIdempotencyService.Claim.ofFresh());
        when(cartService.getItems(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> controller().create(CUSTOMER, "key-1",
                new CreateOrderRequest(null, 100L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one item");
        verify(idempotencyService).markFailed(7L, "key-1");
        verify(idempotencyService, never()).complete(any(), any(), any(), anyInt(), any());
    }

    @Test
    void orderFailure_duringSaga_marksFailedAndPropagatesOriginal() {
        when(idempotencyService.claim(eq(7L), eq("key-1"), any()))
                .thenReturn(OrderCreateIdempotencyService.Claim.ofFresh());
        BusinessException sagaFailure = new BusinessException("stock unavailable");
        when(orderService.createOrder(any())).thenThrow(sagaFailure);

        assertThatThrownBy(() -> controller().create(CUSTOMER, "key-1", explicitOrder()))
                .isSameAs(sagaFailure);
        verify(idempotencyService).markFailed(7L, "key-1");
    }

    @Test
    void invalidBody_400BeforeAnyClaim() {
        assertThatThrownBy(() -> controller().create(CUSTOMER, "key-1",
                new CreateOrderRequest(null, null, null)))
                .isInstanceOf(BusinessException.class);
        verify(idempotencyService, never()).claim(any(), any(), any());
        verify(idempotencyService, never()).claim(isNull(), any(), any());
    }
}
