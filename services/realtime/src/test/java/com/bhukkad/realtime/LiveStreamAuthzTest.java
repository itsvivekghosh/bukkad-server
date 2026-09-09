package com.bhukkad.realtime;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.realtime.api.LiveStreamController;
import com.bhukkad.realtime.client.OrderOwnershipClient;
import com.bhukkad.realtime.dto.OrderLiveUpdate;
import com.bhukkad.realtime.service.OrderLiveRelay;
import com.bhukkad.realtime.service.OrderSseStreamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stream privacy (audit D): the customer live stream must never open for a
 * non-owner, and the oracle failing (mesh outage) must fail CLOSED, not open.
 */
@ExtendWith(MockitoExtension.class)
class LiveStreamAuthzTest {

    @Mock private OrderSseStreamService streamService;
    @Mock private OrderOwnershipClient ownershipClient;
    @Mock private OrderLiveRelay relay;
    @InjectMocks private LiveStreamController controller;

    private TokenPrincipal principal(long id, String scope) {
        return new TokenPrincipal(id, "u@t", scope);
    }

    @Test
    void owner_getsCustomerStream() {
        when(ownershipClient.ownsOrder(7L, 42L)).thenReturn(true);
        when(streamService.subscribeCustomer(eq(42L), any(), any())).thenReturn(new SseEmitter());

        assertThat(controller.subscribeCustomer(principal(7L, "CUSTOMER"), 42L, null))
                .isNotNull();
    }

    @Test
    void nonOwner_denied() {
        when(ownershipClient.ownsOrder(8L, 42L)).thenReturn(false);

        assertThatThrownBy(() -> controller.subscribeCustomer(principal(8L, "CUSTOMER"), 42L, null))
                .isInstanceOf(AccessDeniedException.class);
        verify(streamService, never()).subscribeCustomer(any(), any(), any());
    }

    @Test
    void oracleUnavailable_failsClosed() {
        when(ownershipClient.ownsOrder(7L, 42L)).thenReturn(false);

        assertThatThrownBy(() -> controller.subscribeCustomer(principal(7L, "CUSTOMER"), 42L, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void admin_opensWithoutOwnershipProbe() {
        when(streamService.subscribeCustomer(eq(42L), any(), any())).thenReturn(new SseEmitter());

        assertThat(controller.subscribeCustomer(principal(1L, "ADMIN"), 42L, null)).isNotNull();
        verify(ownershipClient, never()).ownsOrder(any(), any());
    }

    @Test
    void unauthenticated_denied() {
        assertThatThrownBy(() -> controller.subscribeCustomer(null, 42L, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void broadcast_routesThroughRelayForCrossPodFanOut() {
        OrderLiveUpdate update = new OrderLiveUpdate();

        controller.broadcastCustomer(42L, update);

        verify(relay).publish(update);
        assertThat(update.getOrderId()).isEqualTo(42L);
        verify(streamService, never()).broadcastCustomer(any(), any());
    }
}
