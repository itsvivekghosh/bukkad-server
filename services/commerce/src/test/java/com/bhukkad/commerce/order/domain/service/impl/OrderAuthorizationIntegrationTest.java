package com.bhukkad.commerce.order.domain.service.impl;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.common.security.authz.AuthorizationService;
import com.bhukkad.commerce.order.api.dto.response.OrderResponse;
import com.bhukkad.commerce.order.domain.authz.OrderOwnershipPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Integration coverage across the real order service, order policy, and
 * platform authorization engine. It proves the public order-read path denies
 * a different customer before returning the order payload.
 */
@ExtendWith(MockitoExtension.class)
class OrderAuthorizationIntegrationTest {

    @Mock private OrderCreationService orderCreationService;
    @Mock private OrderQueryService orderQueryService;
    @Mock private OrderLifecycleService orderLifecycleService;
    @Mock private OrderReorderService orderReorderService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        AuthorizationService authorizationService = new AuthorizationService(
                List.of(new OrderOwnershipPolicy()),
                new SimpleMeterRegistry(),
                null);
        orderService = new OrderService(orderCreationService, orderQueryService,
                orderLifecycleService, orderReorderService, authorizationService);
    }

    @Test
    void crossCustomerOrderReadIsRejected() {
        when(orderQueryService.getOrder(9L)).thenReturn(
                new OrderResponse(9L, 77L, 3L, "CONFIRMED",
                        new BigDecimal("125.00"), List.of()));

        assertThatThrownBy(() -> orderService.getOrder(9L,
                new TokenPrincipal(11L, "other@example.test", "CUSTOMER")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("another customer's order");
    }

    @Test
    void ownerCanReadOwnOrder() {
        when(orderQueryService.getOrder(9L)).thenReturn(
                new OrderResponse(9L, 77L, 3L, "CONFIRMED",
                        new BigDecimal("125.00"), List.of()));

        OrderResponse response = orderService.getOrder(9L,
                new TokenPrincipal(77L, "owner@example.test", "CUSTOMER"));

        assertThat(response.id()).isEqualTo(9L);
    }
}
