package com.bhukkad.zone;

import com.bhukkad.dto.request.DeliveryZoneRequest;
import com.bhukkad.dto.response.DeliveryZoneResponse;
import com.bhukkad.entity.DeliveryZone;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.DeliveryZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin CRUD for delivery zones (V14).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryZoneAdminService {

    private final DeliveryZoneRepository deliveryZoneRepository;

    public List<DeliveryZoneResponse> listAll() {
        return deliveryZoneRepository.findAll().stream().map(this::toResponse).toList();
    }

    public DeliveryZoneResponse getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public DeliveryZoneResponse create(DeliveryZoneRequest request) {
        DeliveryZone zone = new DeliveryZone();
        applyRequest(zone, request);
        return toResponse(deliveryZoneRepository.save(zone));
    }

    @Transactional
    public DeliveryZoneResponse update(Long id, DeliveryZoneRequest request) {
        DeliveryZone zone = findOrThrow(id);
        applyRequest(zone, request);
        return toResponse(deliveryZoneRepository.save(zone));
    }

    @Transactional
    public void delete(Long id) {
        deliveryZoneRepository.delete(findOrThrow(id));
    }

    private DeliveryZone findOrThrow(Long id) {
        return deliveryZoneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery zone not found"));
    }

    private void applyRequest(DeliveryZone zone, DeliveryZoneRequest request) {
        if (request.getName() != null) zone.setName(request.getName());
        zone.setCity(request.getCity() != null && !request.getCity().isBlank() ? request.getCity() : "Default");
        Double lat = request.getCenterLatitude() != null ? request.getCenterLatitude() : request.getLatitude();
        Double lng = request.getCenterLongitude() != null ? request.getCenterLongitude() : request.getLongitude();
        if (lat != null) zone.setCenterLatitude(lat);
        if (lng != null) zone.setCenterLongitude(lng);
        if (request.getRadiusKm() != null) zone.setRadiusKm(request.getRadiusKm());
        if (request.getBaseDeliveryFee() != null) zone.setBaseDeliveryFee(request.getBaseDeliveryFee());
        if (request.getPerKmFee() != null) zone.setPerKmFee(request.getPerKmFee());
        if (request.getSurgeMultiplier() != null) zone.setSurgeMultiplier(request.getSurgeMultiplier());
        if (request.getFreeDeliveryAbove() != null) zone.setFreeDeliveryAbove(request.getFreeDeliveryAbove());
        if (request.getIsActive() != null) zone.setIsActive(request.getIsActive());
    }

    private DeliveryZoneResponse toResponse(DeliveryZone zone) {
        return DeliveryZoneResponse.builder()
                .id(zone.getId())
                .name(zone.getName())
                .city(zone.getCity())
                .centerLatitude(zone.getCenterLatitude())
                .centerLongitude(zone.getCenterLongitude())
                .radiusKm(zone.getRadiusKm())
                .baseDeliveryFee(zone.getBaseDeliveryFee())
                .perKmFee(zone.getPerKmFee())
                .surgeMultiplier(zone.getSurgeMultiplier())
                .freeDeliveryAbove(zone.getFreeDeliveryAbove())
                .isActive(zone.getIsActive())
                .build();
    }
}
