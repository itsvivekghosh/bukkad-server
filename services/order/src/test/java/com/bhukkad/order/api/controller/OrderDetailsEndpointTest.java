package com.bhukkad.order.api.controller;

import com.bhukkad.order.api.controller.OrderController;
import com.bhukkad.order.api.dto.response.OrderDetailsResponse;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The /details endpoint previously exposed ANY order's customerId to ANY
 * authenticated user (javadoc promised service-JWT, nothing enforced).
 * Contract now: owner, ADMIN, or ROLE_SERVICE (mesh client token) only.
 */
@ExtendWith(MockitoExtension.class)
class OrderDetailsEndpointTest {

    @Mock private OrderService orderService;
    @InjectMocks private OrderController controller;

    private OrderDetailsResponse details(Long customerId) {
        return new OrderDetailsResponse(1L, customerId, 9L, "CONFIRMED",
                new BigDecimal("250.00"), null, null);
    }

    private Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("svc", null,
                List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void owner_canReadOwnOrderDetails() {
        when(orderService.getOrderDetails(1L)).thenReturn(details(7L));
        TokenPrincipal owner = new TokenPrincipal(7L, "o@t", "CUSTOMER");

        assertThat(controller.getOrderDetails(1L, owner, auth("ROLE_CUSTOMER")).customerId())
                .isEqualTo(7L);
    }

    @Test
    void anotherCustomer_isDenied() {
        when(orderService.getOrderDetails(1L)).thenReturn(details(7L));
        TokenPrincipal other = new TokenPrincipal(8L, "x@t", "CUSTOMER");

        assertThatThrownBy(() -> controller.getOrderDetails(1L, other, auth("ROLE_CUSTOMER")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void serviceToken_withoutPrincipal_isPrivileged() {
        when(orderService.getOrderDetails(1L)).thenReturn(details(7L));

        assertThat(controller.getOrderDetails(1L, null, auth("ROLE_SERVICE")).customerId())
                .isEqualTo(7L);
    }

    @Test
    void adminToken_withoutPrincipal_isPrivileged() {
        when(orderService.getOrderDetails(1L)).thenReturn(details(7L));

        assertThat(controller.getOrderDetails(1L, null, auth("ROLE_ADMIN")).customerId())
                .isEqualTo(7L);
    }

    @Test
    void unauthenticated_isDenied() {
        assertThatThrownBy(() -> controller.getOrderDetails(1L, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
