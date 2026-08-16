package com.bhukkad.controller;

import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.User;
import com.bhukkad.live.OrderLiveAccessService;
import com.bhukkad.live.OrderSseStreamService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@Tag("regression")
@ExtendWith(MockitoExtension.class)
public class OrderLiveStreamControllerTest {

    @Mock
    private OrderSseStreamService sseStreamService;

    @Mock
    private OrderLiveAccessService orderLiveAccessService;

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderLiveStreamController controller;

    @Test
    void streamKitchen_verifiesAccessAndSubscribes() {
        User owner = new User();
        owner.setId(5L);
        SseEmitter emitter = new SseEmitter();
        when(securityUtils.getCurrentUser()).thenReturn(owner);
        when(sseStreamService.subscribeKitchen(10L, "42")).thenReturn(emitter);

        SseEmitter result = controller.streamKitchen(10L, "42");

        assertNotNull(result);
        verify(orderLiveAccessService).verifyKitchenAccess(owner, 10L);
        verify(sseStreamService).subscribeKitchen(10L, "42");
    }

    @Test
    void streamRider_subscribesForCurrentAgent() {
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        SseEmitter emitter = new SseEmitter();
        when(sseStreamService.subscribeRider(7L, null)).thenReturn(emitter);

        SseEmitter result = controller.streamRider(null);

        assertNotNull(result);
        verify(sseStreamService).subscribeRider(7L, null);
    }

    @Test
    void streamCustomerOrder_sendsSnapshotAndSupportsLastEventId() {
        User customer = new User();
        customer.setId(2L);
        OrderResponse snapshot = OrderResponse.builder().id(99L).orderNumber("ORD-99").build();
        SseEmitter emitter = new SseEmitter();

        when(securityUtils.getCurrentUser()).thenReturn(customer);
        when(orderService.getOrderById(99L)).thenReturn(snapshot);
        when(sseStreamService.subscribeCustomer(99L, "15", snapshot)).thenReturn(emitter);

        SseEmitter result = controller.streamCustomerOrder(99L, "15");

        assertNotNull(result);
        verify(orderLiveAccessService).verifyCustomerAccess(customer, 99L);
        verify(orderService).getOrderById(99L);
        verify(sseStreamService).subscribeCustomer(99L, "15", snapshot);
    }
}
