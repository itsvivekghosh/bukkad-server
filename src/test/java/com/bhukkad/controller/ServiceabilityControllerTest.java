package com.bhukkad.controller;

import com.bhukkad.cache.ServiceabilityCacheService;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.ServiceabilityResponse;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.zone.DeliveryZoneService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceabilityControllerTest {

    @Mock
    private DeliveryZoneService deliveryZoneService;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private ServiceabilityCacheService serviceabilityCacheService;

    @InjectMocks
    private ServiceabilityController controller;

    @Test void checkServiceability_returnsResponse() {
        ServiceabilityResponse expected = ServiceabilityResponse.builder()
                .serviceable(true).build();
        when(serviceabilityCacheService.getServiceability(anyLong(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(expected);

        ResponseEntity<ApiResponse<ServiceabilityResponse>> resp =
                controller.checkServiceability(10L, 12.9, 77.5, 200.0);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(true, resp.getBody().getData().isServiceable());
        verify(serviceabilityCacheService).getServiceability(
                anyLong(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    @Test void checkServiceability_missingRestaurant_throws() {
        when(restaurantRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());
        when(serviceabilityCacheService.getServiceability(anyLong(), anyDouble(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> {
                    ((java.util.function.Supplier<?>) inv.getArgument(4)).get();
                    return null;
                });

        assertThrows(ResourceNotFoundException.class,
                () -> controller.checkServiceability(999L, 12.9, 77.5, 0.0));
    }
}