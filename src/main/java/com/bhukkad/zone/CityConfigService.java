package com.bhukkad.zone;

import com.bhukkad.dto.request.CityConfigRequest;
import com.bhukkad.dto.response.CityConfigResponse;
import com.bhukkad.entity.CityConfig;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CityConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Per-city platform configuration (Multi-city/Region Support).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CityConfigService {

    private final CityConfigRepository cityConfigRepository;

    public List<CityConfigResponse> listAll() {
        return cityConfigRepository.findAll().stream().map(this::toResponse).toList();
    }

    public List<CityConfigResponse> listActive() {
        return cityConfigRepository.findByIsActiveTrueOrderByCityAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CityConfigResponse create(CityConfigRequest request) {
        if (cityConfigRepository.existsByCityIgnoreCase(request.getCity())) {
            throw new BusinessException("City config already exists for " + request.getCity());
        }
        CityConfig config = new CityConfig();
        applyRequest(config, request);
        return toResponse(cityConfigRepository.save(config));
    }

    @Transactional
    public CityConfigResponse update(Long id, CityConfigRequest request) {
        CityConfig config = cityConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("City config not found"));
        if (request.getCity() != null && !request.getCity().equalsIgnoreCase(config.getCity())
                && cityConfigRepository.existsByCityIgnoreCase(request.getCity())) {
            throw new BusinessException("City config already exists for " + request.getCity());
        }
        applyRequest(config, request);
        return toResponse(cityConfigRepository.save(config));
    }

    @Transactional
    public void delete(Long id) {
        cityConfigRepository.delete(findOrThrow(id));
    }

    private CityConfig findOrThrow(Long id) {
        return cityConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("City config not found"));
    }

    private void applyRequest(CityConfig config, CityConfigRequest request) {
        if (request.getCity() != null) config.setCity(request.getCity().trim());
        if (request.getDisplayName() != null) config.setDisplayName(request.getDisplayName().trim());
        if (request.getCurrency() != null) config.setCurrency(request.getCurrency().trim().toUpperCase());
        if (request.getTimezone() != null) config.setTimezone(request.getTimezone());
        if (request.getSupportedPaymentMethods() != null) {
            config.setSupportedPaymentMethods(request.getSupportedPaymentMethods());
        }
        if (request.getDefaultMinOrderAmount() != null) config.setDefaultMinOrderAmount(request.getDefaultMinOrderAmount());
        if (request.getIsServiceable() != null) config.setIsServiceable(request.getIsServiceable());
        if (request.getIsActive() != null) config.setIsActive(request.getIsActive());
    }

    private CityConfigResponse toResponse(CityConfig config) {
        return CityConfigResponse.builder()
                .id(config.getId())
                .city(config.getCity())
                .displayName(config.getDisplayName())
                .currency(config.getCurrency())
                .timezone(config.getTimezone())
                .supportedPaymentMethods(config.getSupportedPaymentMethods())
                .defaultMinOrderAmount(config.getDefaultMinOrderAmount())
                .isServiceable(config.getIsServiceable())
                .isActive(config.getIsActive())
                .build();
    }
}
