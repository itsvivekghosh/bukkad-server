package com.bhukkad.zone;

import com.bhukkad.dto.request.DeliveryZoneRequest;
import com.bhukkad.dto.response.DeliveryZoneResponse;
import com.bhukkad.entity.DeliveryZone;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.DeliveryZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryZoneAdminServiceTest {

    @Mock
    private DeliveryZoneRepository deliveryZoneRepository;

    @InjectMocks
    private DeliveryZoneAdminService service;

    private DeliveryZone zone;

    @BeforeEach
    void setUp() {
        zone = new DeliveryZone();
        zone.setId(1L);
        zone.setName("Central");
        zone.setCity("Bengaluru");
        zone.setCenterLatitude(12.97);
        zone.setCenterLongitude(77.59);
        zone.setRadiusKm(10.0);
    }

    @Test
    void listAll_mapsZones() {
        when(deliveryZoneRepository.findAll()).thenReturn(List.of(zone));

        List<DeliveryZoneResponse> result = service.listAll();
        assertEquals(1, result.size());
        assertEquals("Central", result.get(0).getName());
    }

    @Test
    void getById_returnsZone() {
        when(deliveryZoneRepository.findById(1L)).thenReturn(Optional.of(zone));

        DeliveryZoneResponse result = service.getById(1L);
        assertEquals("Central", result.getName());
    }

    @Test
    void getById_throwsWhenNotFound() {
        when(deliveryZoneRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getById(99L));
    }

    @Test
    void create_appliesRequestAndSaves() {
        DeliveryZoneRequest request = new DeliveryZoneRequest();
        request.setName("North");
        request.setCity("Mysuru");
        request.setCenterLatitude(12.3);
        request.setCenterLongitude(76.6);
        request.setRadiusKm(8.0);
        when(deliveryZoneRepository.save(any(DeliveryZone.class))).thenAnswer(inv -> inv.getArgument(0));

        DeliveryZoneResponse result = service.create(request);

        assertNotNull(result);
        assertEquals("North", result.getName());
        verify(deliveryZoneRepository).save(any(DeliveryZone.class));
    }

    @Test
    void update_appliesChanges() {
        DeliveryZoneRequest request = new DeliveryZoneRequest();
        request.setName("Renamed");
        request.setRadiusKm(12.0);
        when(deliveryZoneRepository.findById(1L)).thenReturn(Optional.of(zone));
        when(deliveryZoneRepository.save(any(DeliveryZone.class))).thenReturn(zone);

        DeliveryZoneResponse result = service.update(1L, request);

        assertEquals("Renamed", result.getName());
    }

    @Test
    void delete_removesZone() {
        when(deliveryZoneRepository.findById(1L)).thenReturn(Optional.of(zone));

        service.delete(1L);
        verify(deliveryZoneRepository).delete(zone);
    }
}