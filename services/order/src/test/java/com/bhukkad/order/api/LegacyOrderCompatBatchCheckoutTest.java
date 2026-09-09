package com.bhukkad.order.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.client.RestaurantClient;
import com.bhukkad.order.domain.CartItem;
import com.bhukkad.order.service.CartService;
import com.bhukkad.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PERF-3: batch checkout resolves ALL cart lines' restaurants with one S2S
 * batch call (was a per-item {@code .block(5s)} serial loop — 10 items meant
 * up to 10 round-trips with 5 s parking each).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LegacyOrderCompatBatchCheckoutTest {

    @Mock private OrderService orderService;
    @Mock private CartService cartService;
    @Mock private com.bhukkad.order.domain.OrderRepository orderRepository;
    @Mock private RestaurantClient restaurantClient;
    @Mock private com.bhukkad.order.service.OrderCreateJobService orderCreateJobService;
    @Mock private com.bhukkad.order.service.AsyncOrderCreateService asyncOrderCreateService;

    private LegacyOrderCompatController controller() {
        return new LegacyOrderCompatController(orderService, cartService, orderRepository,
                restaurantClient, orderCreateJobService, asyncOrderCreateService);
    }

    private static final TokenPrincipal CUSTOMER =
            new TokenPrincipal(7L, "c@bhukkad.dev", "CUSTOMER");

    private CartItem line(long menuItemId) {
        CartItem item = new CartItem();
        item.setMenuItemId(menuItemId);
        item.setItemName("Line " + menuItemId);
        item.setUnitPrice(new BigDecimal("50.00"));
        item.setQuantity(1);
        return item;
    }

    private Map<String, Object> remoteItem(long id, long restaurantId) {
        return Map.of("id", id, "restaurantId", restaurantId, "name", "N" + id,
                "price", new BigDecimal("50.00"), "available", true);
    }

    @Test
    void createBatch_tenItemCart_resolvesRestaurantsWithOneBatchCall() {
        List<CartItem> lines = new ArrayList<>();
        List<java.util.Map<String, Object>> payload = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            lines.add(line(i));
            payload.add(remoteItem(i, i <= 6 ? 100L : 200L));
        }
        when(cartService.getItems(7L)).thenReturn(lines);
        when(restaurantClient.getMenuItems(anyCollection())).thenReturn(Mono.just(payload));

        List<OrderResponse> orders = controller().createBatch(CUSTOMER, "key-123", null);

        verify(restaurantClient, times(1)).getMenuItems(anyCollection());
        verify(restaurantClient, never()).getMenuItem(any());
        // Two restaurants → two orders, correct grouping (6 + 4 lines).
        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderService, times(2)).createOrder(captor.capture());
        assertThat(captor.getAllValues().get(0).items()).hasSize(6);
        assertThat(captor.getAllValues().get(1).items()).hasSize(4);
        assertThat(captor.getAllValues().get(0).restaurantId()).isEqualTo(100L);
        assertThat(captor.getAllValues().get(1).restaurantId()).isEqualTo(200L);
        verify(cartService).clear(7L);
        verify(orderRepository, never()).findAll();
    }

    @Test
    void createBatch_missingMenuItem_businessErrorSaysRemoveIt() {
        when(cartService.getItems(7L)).thenReturn(List.of(line(42L), line(43L)));
        when(restaurantClient.getMenuItems(anyCollection()))
                .thenReturn(Mono.just(List.of(remoteItem(42L, 9L)))); // 43 deleted

        assertThatThrownBy(() -> controller().createBatch(CUSTOMER, "key", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot determine the restaurant for menu item 43");
    }

    @Test
    void createBatch_restaurantOutage_mapsTo503NotDeletedItems() {
        when(cartService.getItems(7L)).thenReturn(List.of(line(42L)));
        when(restaurantClient.getMenuItems(anyCollection()))
                .thenReturn(Mono.error(new RuntimeException("connect timed out")));

        assertThatThrownBy(() -> controller().createBatch(CUSTOMER, "key", null))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void createBatch_unauthenticated_neverTouchesRestaurant() {
        assertThatThrownBy(() -> controller().createBatch(null, "key", null))
                .isInstanceOf(UnauthorizedException.class);
        verify(restaurantClient, never()).getMenuItems(anyList());
    }
}
