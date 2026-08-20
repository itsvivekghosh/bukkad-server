package com.bhukkad.zone;

import com.bhukkad.dto.request.CityConfigRequest;
import com.bhukkad.entity.CityConfig;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CityConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CityConfigServiceTest {

    @Mock
    private CityConfigRepository cityConfigRepository;

    @InjectMocks
    private CityConfigService service;

    private CityConfigRequest validRequest() {
        CityConfigRequest request = new CityConfigRequest();
        request.setCity("Pune");
        request.setDisplayName("Pune");
        request.setCurrency("INR");
        request.setTimezone("Asia/Kolkata");
        request.setDefaultMinOrderAmount(149.0);
        return request;
    }

    @Test
    void create_savesConfigWithDefaults() {
        when(cityConfigRepository.existsByCityIgnoreCase("Pune")).thenReturn(false);
        when(cityConfigRepository.save(any(CityConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.create(validRequest());

        assertEquals("Pune", response.getCity());
        assertEquals("INR", response.getCurrency());
        assertEquals(149.0, response.getDefaultMinOrderAmount());
        assertTrue(response.getIsActive());
        assertTrue(response.getIsServiceable());
        verify(cityConfigRepository).save(any(CityConfig.class));
    }

    @Test
    void create_duplicateCity_throwsBusinessException() {
        when(cityConfigRepository.existsByCityIgnoreCase("Pune")).thenReturn(true);

        assertThrows(BusinessException.class, () -> service.create(validRequest()));
        verify(cityConfigRepository, never()).save(any());
    }

    @Test
    void update_existingConfig_appliesChanges() {
        CityConfig existing = new CityConfig();
        existing.setId(1L);
        existing.setCity("Pune");
        existing.setDisplayName("Pune");

        when(cityConfigRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(cityConfigRepository.save(any(CityConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        CityConfigRequest request = validRequest();
        request.setCurrency("USD");
        request.setIsServiceable(false);

        var response = service.update(1L, request);

        assertEquals("USD", response.getCurrency());
        assertFalse(response.getIsServiceable());
    }

    @Test
    void update_unknownId_throwsNotFound() {
        when(cityConfigRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.update(99L, validRequest()));
    }

    @Test
    void update_toDuplicateCity_throwsBusinessException() {
        CityConfig existing = new CityConfig();
        existing.setId(1L);
        existing.setCity("Pune");
        existing.setDisplayName("Pune");

        when(cityConfigRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(cityConfigRepository.existsByCityIgnoreCase("Mumbai")).thenReturn(true);

        CityConfigRequest request = validRequest();
        request.setCity("Mumbai");

        assertThrows(BusinessException.class, () -> service.update(1L, request));
    }

    @Test
    void listActive_returnsOnlyActiveCities() {
        CityConfig active = new CityConfig();
        active.setId(1L);
        active.setCity("Pune");
        active.setDisplayName("Pune");
        active.setIsActive(true);

        when(cityConfigRepository.findByIsActiveTrueOrderByCityAsc()).thenReturn(List.of(active));

        var result = service.listActive();

        assertEquals(1, result.size());
        assertEquals("Pune", result.get(0).getCity());
    }

    @Test
    void delete_removesConfig() {
        CityConfig existing = new CityConfig();
        existing.setId(1L);
        existing.setCity("Pune");

        when(cityConfigRepository.findById(1L)).thenReturn(Optional.of(existing));

        service.delete(1L);

        verify(cityConfigRepository).delete(existing);
    }

    @Test
    void delete_unknownId_throwsNotFound() {
        when(cityConfigRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(1L));
    }
}
