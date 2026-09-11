package com.bhukkad.order.api;

import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Audit HIGH-IDOR-3: {@code requireRestaurantOwnerOrAdmin} used to check only
 * the RESTAURANT_OWNER scope — any owner could read/manage any other owner's
 * restaurant orders. The restaurant ownership oracle must confirm the caller
 * owns the addressed restaurant; unknown restaurants and oracle outages fail
 * closed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderOpsRestaurantOwnershipTest {

    private static final Long RESTAURANT_ID = 100L;
    private static final TokenPrincipal OWNER =
            new TokenPrincipal(7L, "owner@bhukkad.dev", "RESTAURANT_OWNER");
    private static final TokenPrincipal OTHER_OWNER =
            new TokenPrincipal(8L, "other@bhukkad.dev", "RESTAURANT_OWNER");
    private static final TokenPrincipal ADMIN =
            new TokenPrincipal(1L, "admin@bhukkad.dev", "ADMIN");

    @Mock private OrderService orderService;
    @Mock private OrderRepository orderRepository;
    @Mock private RestaurantOwnerResolver restaurantOwnerResolver;
    @InjectMocks private OrderOpsController controller;

    @Test
    void owner_ofRestaurant_readsRestaurantOrders() {
        when(restaurantOwnerResolver.ownerIdOf(RESTAURANT_ID)).thenReturn(7L);
        when(orderRepository.findByRestaurantId(RESTAURANT_ID)).thenReturn(List.of());

        assertThat(controller.restaurantOrders(OWNER, RESTAURANT_ID, 0, 10))
                .containsKey("items");
    }

    @Test
    void owner_ofAnotherRestaurant_denied() {
        when(restaurantOwnerResolver.ownerIdOf(RESTAURANT_ID)).thenReturn(7L);

        assertThatThrownBy(() -> controller.restaurantOrders(OTHER_OWNER, RESTAURANT_ID, 0, 10))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Not your restaurant");
        verify(orderRepository, never()).findByRestaurantId(any());
    }

    @Test
    void unknownRestaurant_failsClosed() {
        when(restaurantOwnerResolver.ownerIdOf(RESTAURANT_ID)).thenReturn(null);

        assertThatThrownBy(() -> controller.restaurantOrders(OWNER, RESTAURANT_ID, 0, 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void oracleOutage_failsClosedWith503NotAPass() {
        when(restaurantOwnerResolver.ownerIdOf(RESTAURANT_ID))
                .thenThrow(new UpstreamUnavailableException("restaurant",
                        new RuntimeException("connection refused")));

        assertThatThrownBy(() -> controller.restaurantOrders(OWNER, RESTAURANT_ID, 0, 10))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void admin_bypassesOwnershipProbe() {
        when(orderRepository.findByRestaurantId(RESTAURANT_ID)).thenReturn(List.of());

        assertThat(controller.restaurantOrders(ADMIN, RESTAURANT_ID, 0, 10))
                .containsKey("items");
        verifyNoInteractions(restaurantOwnerResolver);
    }

    @Test
    void nonOwnerScope_deniedBeforeOwnershipProbe() {
        assertThatThrownBy(() -> controller.restaurantOrders(
                new TokenPrincipal(9L, "c@bhukkad.dev", "CUSTOMER"), RESTAURANT_ID, 0, 10))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Owner access required");
        verifyNoInteractions(restaurantOwnerResolver);
    }

    @Test
    void lifecycleTransition_ownerOfAnotherRestaurant_denied() {
        Order order = new Order();
        order.setId(11L);
        order.setRestaurantId(RESTAURANT_ID);
        when(orderRepository.findById(11L)).thenReturn(java.util.Optional.of(order));
        when(restaurantOwnerResolver.ownerIdOf(RESTAURANT_ID)).thenReturn(7L);

        assertThatThrownBy(() -> controller.acceptOrder(OTHER_OWNER, 11L))
                .isInstanceOf(AccessDeniedException.class);
        verify(orderService, never()).transition(any(), any(), any());
    }
}
