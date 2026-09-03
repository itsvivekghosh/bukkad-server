package com.bhukkad.grpc;

import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.grpc.proto.GetOrderStatusRequest;
import com.bhukkad.grpc.proto.OrderStatusSnapshot;
import com.bhukkad.service.OrderService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GrpcOrderStatusServiceTest {

    @Mock
    private OrderService orderService;

    @Mock
    private StreamObserver<OrderStatusSnapshot> responseObserver;

    private GrpcOrderStatusService service;

    @BeforeEach
    void setUp() {
        service = new GrpcOrderStatusService(orderService);
    }

    @Test
    void getOrderStatus_success_returnsSnapshot() {
        GetOrderStatusRequest request = GetOrderStatusRequest.newBuilder().setOrderId(42L).build();
        OrderResponse order = new OrderResponse();
        order.setId(42L);
        order.setOrderNumber("ORD-1");
        order.setStatus("DELIVERED");
        order.setTotalAmount(100.0);
        order.setCustomerId(7L);
        order.setRestaurantId(3L);
        order.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        when(orderService.getOrderById(42L)).thenReturn(order);

        service.getOrderStatus(request, responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(OrderStatusSnapshot.class);
        verify(responseObserver).onNext(captor.capture());
        assertEquals(42L, captor.getValue().getOrderId());
        assertEquals("ORD-1", captor.getValue().getOrderNumber());
        assertEquals("DELIVERED", captor.getValue().getStatus());
        assertEquals(100.0, captor.getValue().getTotalAmount(), 0.001);
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());
    }

    @Test
    void getOrderStatus_partialFields_usesDefaults() {
        GetOrderStatusRequest request = GetOrderStatusRequest.newBuilder().setOrderId(42L).build();
        OrderResponse order = new OrderResponse();
        order.setId(null);
        order.setOrderNumber(null);
        order.setStatus(null);
        order.setTotalAmount(null);
        order.setCustomerId(null);
        order.setRestaurantId(null);
        order.setCreatedAt(null);
        when(orderService.getOrderById(42L)).thenReturn(order);

        service.getOrderStatus(request, responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(OrderStatusSnapshot.class);
        verify(responseObserver).onNext(captor.capture());
        assertEquals(0, captor.getValue().getOrderId());
        assertEquals("", captor.getValue().getOrderNumber());
        assertEquals("", captor.getValue().getStatus());
        assertEquals(0.0, captor.getValue().getTotalAmount(), 0.001);
        verify(responseObserver).onCompleted();
    }

    @Test
    void getOrderStatus_notFound_returnsNotFoundError() {
        GetOrderStatusRequest request = GetOrderStatusRequest.newBuilder().setOrderId(999L).build();
        when(orderService.getOrderById(999L)).thenThrow(new ResourceNotFoundException("Order not found"));

        service.getOrderStatus(request, responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(captor.capture());
        assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(captor.getValue()).getCode());
        assertTrue(captor.getValue().getMessage().contains("999"));
        verify(responseObserver, never()).onNext(any());
    }

    @Test
    void getOrderStatus_internalError_returnsInternalError() {
        GetOrderStatusRequest request = GetOrderStatusRequest.newBuilder().setOrderId(42L).build();
        when(orderService.getOrderById(42L)).thenThrow(new IllegalStateException("db down"));

        service.getOrderStatus(request, responseObserver);

        var captor = org.mockito.ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(captor.capture());
        assertEquals(Status.Code.INTERNAL, Status.fromThrowable(captor.getValue()).getCode());
        verify(responseObserver, never()).onNext(any());
        verify(responseObserver, never()).onCompleted();
    }
}