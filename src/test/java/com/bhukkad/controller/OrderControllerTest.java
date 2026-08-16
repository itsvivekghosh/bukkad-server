package com.bhukkad.controller;

import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.request.DeliveryProofVerifyRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.DeliveryProofPhotoUploadResponse;
import com.bhukkad.dto.response.DeliveryProofResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.OrderSummaryResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.delivery.DeliveryProofService;
import com.bhukkad.fraud.FraudDetectionService;
import com.bhukkad.fraud.FraudEventTypes;
import com.bhukkad.order.AsyncOrderCreateService;
import com.bhukkad.order.OrderCreateJobService;
import com.bhukkad.dto.response.ReorderResponse;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CartService;
import com.bhukkad.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;
    @Mock
    private CartService cartService;
    @Mock
    private AsyncOrderCreateService asyncOrderCreateService;
    @Mock
    private OrderCreateJobService orderCreateJobService;
    /**
     * V17 fraud enforcement: only {@code createOrder} consults these two, so they are stubbed
     * inside that test rather than in a shared setup — a global stub would trip Mockito's
     * strict-stub checking for every other test in this class.
     */
    @Mock
    private FraudDetectionService fraudDetectionService;
    @Mock
    private SecurityUtils securityUtils;
    /**
     * V17 delivery proof. No test here drives the rider proof endpoints, but the controller is
     * built by constructor injection, so declaring the mock keeps the dependency from being
     * injected as {@code null}.
     */
    @Mock
    private DeliveryProofService deliveryProofService;

    @InjectMocks
    private OrderController orderController;

    /**
     * The fraud check runs before the order is built, and unlike the auth endpoints it attributes
     * the event to a known customer id, so the resolved principal must reach
     * {@link FraudDetectionService#checkAndBlock(Long, String)}.
     */
    @Test
    void createOrder_returnsPlacedOrder() {
        OrderRequest request = new OrderRequest();
        OrderResponse order = new OrderResponse();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(orderService.createOrder(request, null)).thenReturn(order);

        ResponseEntity<ApiResponse<?>> response = orderController.createOrder(request, null, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Order placed successfully", response.getBody().getMessage());
        assertEquals(order, response.getBody().getData());
        verify(fraudDetectionService).checkAndBlock(5L, FraudEventTypes.ORDER_CREATE);
    }

    @Test
    void getMyOrders_returnsCustomerOrders() {
        PagedResponse<OrderSummaryResponse> orders = PagedResponse.from(
                new org.springframework.data.domain.PageImpl<>(List.of(new OrderSummaryResponse())));
        when(orderService.getCustomerOrders(0, 20)).thenReturn(orders);

        ResponseEntity<ApiResponse<PagedResponse<OrderSummaryResponse>>> response =
                orderController.getMyOrders(0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(orders, response.getBody().getData());
    }

    @Test
    void getOrderById_returnsOrder() {
        OrderResponse order = new OrderResponse();
        when(orderService.getOrderById(11L)).thenReturn(order);

        ResponseEntity<ApiResponse<OrderResponse>> response = orderController.getOrderById(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(order, response.getBody().getData());
    }

    @Test
    void trackOrder_returnsOrder() {
        OrderResponse order = new OrderResponse();
        when(orderService.trackOrder(11L)).thenReturn(order);

        ResponseEntity<ApiResponse<OrderResponse>> response = orderController.trackOrder(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(order, response.getBody().getData());
    }

    @Test
    void cancelOrder_returnsCancelledOrder() {
        OrderResponse order = new OrderResponse();
        when(orderService.cancelOrder(11L, "changed mind")).thenReturn(order);

        ResponseEntity<ApiResponse<OrderResponse>> response = orderController.cancelOrder(11L, "changed mind");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Order cancelled successfully", response.getBody().getMessage());
        assertEquals(order, response.getBody().getData());
    }

    @Test
    void getRestaurantOrders_returnsPagedList() {
        PagedResponse<OrderSummaryResponse> orders = PagedResponse.from(
                new org.springframework.data.domain.PageImpl<>(List.of(new OrderSummaryResponse())));
        when(orderService.getRestaurantOrders(7L, 0, 20)).thenReturn(orders);

        ResponseEntity<ApiResponse<PagedResponse<OrderSummaryResponse>>> response =
                orderController.getRestaurantOrders(7L, 0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(orders, response.getBody().getData());
    }

    @Test
    void getPendingOrders_returnsList() {
        List<OrderSummaryResponse> orders = List.of(new OrderSummaryResponse());
        when(orderService.getPendingOrdersForRestaurant(7L, 50)).thenReturn(orders);

        ResponseEntity<ApiResponse<List<OrderSummaryResponse>>> response =
                orderController.getPendingOrders(7L, 50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(orders, response.getBody().getData());
    }

    @Test
    void getKitchenQueue_returnsActiveOrders() {
        List<OrderSummaryResponse> orders = List.of(new OrderSummaryResponse());
        when(orderService.getKitchenActiveOrders(7L, 50)).thenReturn(orders);

        ResponseEntity<ApiResponse<List<OrderSummaryResponse>>> response =
                orderController.getKitchenQueue(7L, 50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(orders, response.getBody().getData());
    }

    @Test
    void acceptOrder_returnsAccepted() {
        OrderResponse order = new OrderResponse();
        when(orderService.acceptOrder(11L)).thenReturn(order);

        ResponseEntity<ApiResponse<OrderResponse>> response = orderController.acceptOrder(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Order accepted", response.getBody().getMessage());
        assertEquals(order, response.getBody().getData());
    }

    @Test
    void markOrderReady_returnsReady() {
        OrderResponse order = new OrderResponse();
        when(orderService.markOrderReady(11L)).thenReturn(order);

        ResponseEntity<ApiResponse<OrderResponse>> response = orderController.markOrderReady(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Order marked as ready", response.getBody().getMessage());
        assertEquals(order, response.getBody().getData());
    }

    @Test
    void assignDeliveryAgent_returnsAssigned() {
        OrderResponse order = new OrderResponse();
        when(orderService.assignDeliveryAgent(11L, 22L)).thenReturn(order);

        ResponseEntity<ApiResponse<OrderResponse>> response = orderController.assignDeliveryAgent(11L, 22L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Delivery agent assigned", response.getBody().getMessage());
        assertEquals(order, response.getBody().getData());
    }

    @Test
    void getMyDeliveries_callsServiceWithNullAgentId() {
        PagedResponse<OrderSummaryResponse> orders = PagedResponse.from(
                new org.springframework.data.domain.PageImpl<>(List.of(new OrderSummaryResponse())));
        when(orderService.getDeliveryAgentOrders(null, 0, 20)).thenReturn(orders);

        ResponseEntity<ApiResponse<PagedResponse<OrderSummaryResponse>>> response =
                orderController.getMyDeliveries(0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(orders, response.getBody().getData());
        verify(orderService).getDeliveryAgentOrders(null, 0, 20);
    }

    @Test
    void markOrderPickedUp_updatesStatusToOutForDelivery() {
        OrderResponse order = new OrderResponse();
        when(orderService.updateDeliveryStatus(11L, Order.OrderStatus.OUT_FOR_DELIVERY)).thenReturn(order);

        ResponseEntity<ApiResponse<OrderResponse>> response = orderController.markOrderPickedUp(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Order picked up", response.getBody().getMessage());
        assertEquals(order, response.getBody().getData());
        verify(orderService).updateDeliveryStatus(11L, Order.OrderStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void markOrderDelivered_returnsDelivered() {
        OrderResponse order = new OrderResponse();
        when(orderService.markOrderDelivered(11L)).thenReturn(order);

        ResponseEntity<ApiResponse<OrderResponse>> response = orderController.markOrderDelivered(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Order delivered successfully", response.getBody().getMessage());
        assertEquals(order, response.getBody().getData());
    }

    @Test
    void reorder_delegatesToCartService() {
        ReorderResponse reorderResponse = ReorderResponse.builder().build();
        when(cartService.reorderFromOrder(7L)).thenReturn(reorderResponse);

        ResponseEntity<ApiResponse<ReorderResponse>> response = orderController.reorder(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Items added to cart", response.getBody().getMessage());
        assertEquals(reorderResponse, response.getBody().getData());
    }

    @Test
    void getOrderByNumber_returnsOrder() {
        OrderResponse order = new OrderResponse();
        when(orderService.getOrderByNumber("ORD-1")).thenReturn(order);

        ResponseEntity<ApiResponse<OrderResponse>> response = orderController.getOrderByNumber("ORD-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(order, response.getBody().getData());
    }

    @Test
    void issueDeliveryProofOtp_delegatesToService() {
        DeliveryProofResponse proof = DeliveryProofResponse.builder().orderId(11L).build();
        when(deliveryProofService.issueOtp(11L)).thenReturn(proof);

        ResponseEntity<ApiResponse<DeliveryProofResponse>> response =
                orderController.issueDeliveryProofOtp(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Delivery OTP sent to customer", response.getBody().getMessage());
        assertEquals(proof, response.getBody().getData());
    }

    @Test
    void verifyDeliveryProof_delegatesToService() {
        DeliveryProofVerifyRequest request = new DeliveryProofVerifyRequest();
        request.setOtpCode("123456");
        DeliveryProofResponse proof = DeliveryProofResponse.builder().status("VERIFIED").build();
        when(deliveryProofService.verify(11L, request)).thenReturn(proof);

        ResponseEntity<ApiResponse<DeliveryProofResponse>> response =
                orderController.verifyDeliveryProof(11L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Delivery proof verified", response.getBody().getMessage());
        assertEquals(proof, response.getBody().getData());
    }

    @Test
    void createDeliveryProofPhotoUrl_mapsUploadFields() {
        DeliveryProofService.PhotoUpload upload =
                new DeliveryProofService.PhotoUpload("https://upload", "delivery-proofs/key.jpg");
        when(deliveryProofService.createPhotoUploadUrl(11L, "image/jpeg")).thenReturn(upload);

        var request = new com.bhukkad.dto.request.DeliveryProofPhotoUploadRequest();
        request.setContentType("image/jpeg");

        ResponseEntity<ApiResponse<DeliveryProofPhotoUploadResponse>> response =
                orderController.createDeliveryProofPhotoUrl(11L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("https://upload", response.getBody().getData().getUploadUrl());
        assertEquals("delivery-proofs/key.jpg", response.getBody().getData().getPhotoKey());
    }

    @Test
    void getDeliveryProof_returnsCurrentState() {
        DeliveryProofResponse proof = DeliveryProofResponse.builder().orderId(11L).build();
        when(deliveryProofService.getForAgent(11L)).thenReturn(proof);

        ResponseEntity<ApiResponse<DeliveryProofResponse>> response =
                orderController.getDeliveryProof(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(proof, response.getBody().getData());
    }
}
