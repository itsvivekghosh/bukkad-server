package com.bhukkad.controller;

import com.bhukkad.dto.request.RiderLocationRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.DeliveryAgentResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.dto.response.RiderBatchResponse;
import com.bhukkad.dto.response.RiderEarningsSummaryResponse;
import com.bhukkad.dto.response.RiderLocationResponse;
import com.bhukkad.dto.response.RiderPayoutResponse;
import com.bhukkad.delivery.RiderBatchDispatchService;
import com.bhukkad.delivery.RiderLocationService;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.service.DeliveryService;
import com.bhukkad.service.RiderPayoutService;
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
import org.junit.jupiter.api.Tag;

@Tag("regression")
@ExtendWith(MockitoExtension.class)
public class DeliveryControllerTest {

    @Mock
    private DeliveryService deliveryService;
    @Mock
    private RiderPayoutService riderPayoutService;
    @Mock
    private RiderLocationService riderLocationService;
    @Mock
    private RiderBatchDispatchService riderBatchDispatchService;

    @InjectMocks
    private DeliveryController deliveryController;

    @Test
    void getProfile_returnsCurrentAgent() {
        DeliveryAgentResponse agent = DeliveryAgentResponse.builder().id(8L).fullName("Ravi").build();
        when(deliveryService.getProfile()).thenReturn(agent);

        ResponseEntity<ApiResponse<DeliveryAgentResponse>> response = deliveryController.getProfile();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(agent, response.getBody().getData());
        verify(deliveryService).getProfile();
    }

    @Test
    void updateProfile_loadsCurrentAgentThenUpdates() {
        DeliveryAgent current = new DeliveryAgent();
        current.setId(8L);
        DeliveryAgent payload = new DeliveryAgent();
        DeliveryAgentResponse updated = DeliveryAgentResponse.builder().id(8L).build();

        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(current);
        when(deliveryService.updateProfile(8L, payload)).thenReturn(updated);

        ResponseEntity<ApiResponse<DeliveryAgentResponse>> response = deliveryController.updateProfile(payload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Profile updated successfully", response.getBody().getMessage());
        assertEquals(updated, response.getBody().getData());
        verify(deliveryService).getCurrentDeliveryAgent();
        verify(deliveryService).updateProfile(8L, payload);
    }

    @Test
    void toggleAvailability_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = deliveryController.toggleAvailability(true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Availability updated", response.getBody().getMessage());
        verify(deliveryService).toggleAvailability(true);
    }

    @Test
    void updateLocation_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = deliveryController.updateLocation(12.9, 77.6);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Location updated", response.getBody().getMessage());
        verify(deliveryService).updateLocation(12.9, 77.6);
    }

    @Test
    void getActiveDeliveries_returnsList() {
        List<OrderResponse> deliveries = List.of(new OrderResponse());
        when(deliveryService.getActiveDeliveries()).thenReturn(deliveries);

        ResponseEntity<ApiResponse<List<OrderResponse>>> response = deliveryController.getActiveDeliveries();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(deliveries, response.getBody().getData());
    }

    @Test
    void getDeliveryHistory_returnsList() {
        List<OrderResponse> history = List.of(new OrderResponse());
        when(deliveryService.getDeliveryHistory()).thenReturn(history);

        ResponseEntity<ApiResponse<List<OrderResponse>>> response = deliveryController.getDeliveryHistory();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(history, response.getBody().getData());
    }

    @Test
    void acceptDelivery_returnsOrder() {
        OrderResponse order = new OrderResponse();
        when(deliveryService.acceptDelivery(11L)).thenReturn(order);

        ResponseEntity<ApiResponse<OrderResponse>> response = deliveryController.acceptDelivery(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Delivery accepted", response.getBody().getMessage());
        assertEquals(order, response.getBody().getData());
    }

    @Test
    void getEarningsSummary_returnsSummary() {
        RiderEarningsSummaryResponse summary = RiderEarningsSummaryResponse.builder().build();
        when(riderPayoutService.getEarningsSummary()).thenReturn(summary);

        ResponseEntity<ApiResponse<RiderEarningsSummaryResponse>> response = deliveryController.getEarningsSummary();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(summary, response.getBody().getData());
    }

    @Test
    void getEarningsHistory_returnsPagedHistory() {
        PagedResponse<RiderPayoutResponse> page = PagedResponse.<RiderPayoutResponse>builder().build();
        when(riderPayoutService.getPayoutHistory(0, 20)).thenReturn(page);

        ResponseEntity<ApiResponse<PagedResponse<RiderPayoutResponse>>> response =
                deliveryController.getEarningsHistory(0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(page, response.getBody().getData());
    }

    @Test
    void getEarningsHistoryByCursor_returnsCursorPage() {
        CursorPagedResponse<RiderPayoutResponse> page =
                CursorPagedResponse.<RiderPayoutResponse>builder().build();
        when(riderPayoutService.getPayoutHistoryByCursor("c1", 25)).thenReturn(page);

        ResponseEntity<ApiResponse<CursorPagedResponse<RiderPayoutResponse>>> response =
                deliveryController.getEarningsHistoryByCursor("c1", 25);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(page, response.getBody().getData());
    }

    @Test
    void rejectDelivery_returnsOrder() {
        OrderResponse order = new OrderResponse();
        when(deliveryService.rejectDelivery(11L)).thenReturn(order);

        ResponseEntity<ApiResponse<OrderResponse>> response = deliveryController.rejectDelivery(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Delivery rejected", response.getBody().getMessage());
        assertEquals(order, response.getBody().getData());
    }

    @Test
    void getAvailableOrders_returnsOrders() {
        List<OrderResponse> orders = List.of(new OrderResponse());
        when(deliveryService.getAvailableOrders()).thenReturn(orders);

        ResponseEntity<ApiResponse<List<OrderResponse>>> response = deliveryController.getAvailableOrders();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(orders, response.getBody().getData());
    }

    @Test
    void updateOrderLocation_recordsLocation() {
        RiderLocationRequest request = new RiderLocationRequest();
        RiderLocationResponse location = RiderLocationResponse.builder().build();
        when(riderLocationService.recordLocation(5L, request)).thenReturn(location);

        ResponseEntity<ApiResponse<RiderLocationResponse>> response =
                deliveryController.updateOrderLocation(5L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Location recorded", response.getBody().getMessage());
        assertEquals(location, response.getBody().getData());
    }

    @Test
    void createDeliveryBatch_returnsBatch() {
        RiderBatchResponse batch = RiderBatchResponse.builder().build();
        when(riderBatchDispatchService.createBatchFromActiveOrders()).thenReturn(batch);

        ResponseEntity<ApiResponse<RiderBatchResponse>> response = deliveryController.createDeliveryBatch();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Delivery batch created", response.getBody().getMessage());
        assertEquals(batch, response.getBody().getData());
    }

    @Test
    void getActiveBatch_returnsBatch() {
        RiderBatchResponse batch = RiderBatchResponse.builder().build();
        when(riderBatchDispatchService.getActiveBatch()).thenReturn(batch);

        ResponseEntity<ApiResponse<RiderBatchResponse>> response = deliveryController.getActiveBatch();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(batch, response.getBody().getData());
    }

    @Test
    void completeBatch_returnsCompletedBatch() {
        RiderBatchResponse batch = RiderBatchResponse.builder().build();
        when(riderBatchDispatchService.completeBatch(3L)).thenReturn(batch);

        ResponseEntity<ApiResponse<RiderBatchResponse>> response = deliveryController.completeBatch(3L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Batch completed", response.getBody().getMessage());
        assertEquals(batch, response.getBody().getData());
    }
}
