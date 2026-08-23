package com.bhukkad.zone;

import com.bhukkad.dto.response.ServiceabilityResponse;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.DeliveryZone;
import com.bhukkad.entity.Restaurant;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryZoneServiceTest {

    @Mock
    private DeliveryZoneRepository deliveryZoneRepository;
    @Mock
    private ZoneSurgeService zoneSurgeService;

    @InjectMocks
    private DeliveryZoneService service;

    private DeliveryZone zone;
    private Restaurant restaurant;

    @BeforeEach
    void setUp() {
        zone = new DeliveryZone();
        zone.setId(1L);
        zone.setName("Central");
        zone.setCenterLatitude(12.97);
        zone.setCenterLongitude(77.59);
        zone.setRadiusKm(10.0);
        zone.setBaseDeliveryFee(30.0);
        zone.setPerKmFee(5.0);
        zone.setFreeDeliveryAbove(300.0);

        Address address = new Address();
        address.setLatitude(12.97);
        address.setLongitude(77.59);

        restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setAddress(address);
    }

    @Test
    void findZoneForCoordinates_returnsNearestMatchingZone() {
        DeliveryZone far = new DeliveryZone();
        far.setId(2L);
        far.setCenterLatitude(13.0);
        far.setCenterLongitude(77.6);
        far.setRadiusKm(50.0);

        when(deliveryZoneRepository.findByIsActiveTrue()).thenReturn(List.of(far, zone));

        Optional<DeliveryZone> result = service.findZoneForCoordinates(12.97, 77.59);
        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
    }

    @Test
    void findZoneForCoordinates_returnsEmpty_whenNoZoneContains() {
        zone.setRadiusKm(0.001);
        when(deliveryZoneRepository.findByIsActiveTrue()).thenReturn(List.of(zone));

        Optional<DeliveryZone> result = service.findZoneForCoordinates(13.2, 77.7);
        assertFalse(result.isPresent());
    }

    @Test
    void calculateDeliveryFee_usesDefaultWhenNoZone() {
        when(deliveryZoneRepository.findByIsActiveTrue()).thenReturn(List.of());

        double fee = service.calculateDeliveryFee(restaurant, 100, 12.98, 77.60);
        assertTrue(fee > 0);
    }

    @Test
    void calculateDeliveryFee_appliesFreeDelivery() {
        when(deliveryZoneRepository.findByIsActiveTrue()).thenReturn(List.of(zone));

        double fee = service.calculateDeliveryFee(restaurant, 500, 12.97, 77.59);
        assertEquals(0.0, fee);
    }

    @Test
    void calculateDeliveryFee_appliesZonePricingAndSurge() {
        when(deliveryZoneRepository.findByIsActiveTrue()).thenReturn(List.of(zone));
        when(zoneSurgeService.resolveEffectiveSurge(zone)).thenReturn(1.2);

        double fee = service.calculateDeliveryFee(restaurant, 100, 12.97, 77.59);
        // (30 + 5*0) * 1.2 = 36
        assertEquals(36.0, fee);
    }

    @Test
    void isServiceable_returnsUnserviceable_whenNoZone() {
        when(deliveryZoneRepository.findByIsActiveTrue()).thenReturn(List.of());

        ServiceabilityResponse result = service.isServiceable(restaurant, 100, 12.98, 77.60);
        assertFalse(result.isServiceable());
    }

    @Test
    void isServiceable_returnsServiceable_whenZoneFound() {
        when(deliveryZoneRepository.findByIsActiveTrue()).thenReturn(List.of(zone));
        when(zoneSurgeService.resolveEffectiveSurge(zone)).thenReturn(1.0);

        ServiceabilityResponse result = service.isServiceable(restaurant, 100, 12.97, 77.59);
        assertTrue(result.isServiceable());
        assertEquals(1L, result.getZoneId());
        assertEquals(30.0, result.getEstimatedDeliveryFee());
    }
}