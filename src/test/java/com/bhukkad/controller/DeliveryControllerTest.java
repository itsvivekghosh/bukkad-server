package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.DeliveryAgentResponse;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.service.DeliveryService;
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
class DeliveryControllerTest {

    @Mock
    private DeliveryService deliveryService;

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
        DeliveryAgent updated = new DeliveryAgent();
        updated.setId(8L);

        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(current);
        when(deliveryService.updateProfile(8L, payload)).thenReturn(updated);

        ResponseEntity<ApiResponse<DeliveryAgent>> response = deliveryController.updateProfile(payload);

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
}
