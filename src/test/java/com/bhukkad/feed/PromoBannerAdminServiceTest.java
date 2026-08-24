package com.bhukkad.feed;

import com.bhukkad.dto.request.PromoBannerRequest;
import com.bhukkad.dto.response.PromoBannerResponse;
import com.bhukkad.entity.PromoBanner;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.PromoBannerRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromoBannerAdminServiceTest {

    @Mock
    private PromoBannerRepository promoBannerRepository;

    @InjectMocks
    private PromoBannerAdminService service;

    private PromoBanner banner;

    @BeforeEach
    void setUp() {
        banner = new PromoBanner();
        banner.setId(1L);
        banner.setTitle("Weekend Sale");
        banner.setIsActive(true);
    }

    @Test
    void listAll_mapsBanners() {
        when(promoBannerRepository.findAll()).thenReturn(List.of(banner));

        List<PromoBannerResponse> result = service.listAll();
        assertEquals(1, result.size());
        assertEquals("Weekend Sale", result.get(0).getTitle());
    }

    @Test
    void create_savesBanner() {
        PromoBannerRequest request = new PromoBannerRequest();
        request.setTitle("New Banner");
        request.setActionType("RESTAURANT");
        when(promoBannerRepository.save(any(PromoBanner.class))).thenAnswer(inv -> inv.getArgument(0));

        PromoBannerResponse result = service.create(request);

        assertEquals("New Banner", result.getTitle());
    }

    @Test
    void update_modifiesBanner() {
        PromoBannerRequest request = new PromoBannerRequest();
        request.setTitle("Updated Title");
        when(promoBannerRepository.findById(1L)).thenReturn(Optional.of(banner));
        when(promoBannerRepository.save(any(PromoBanner.class))).thenReturn(banner);

        PromoBannerResponse result = service.update(1L, request);

        assertEquals("Updated Title", result.getTitle());
    }

    @Test
    void deactivate_disablesBanner() {
        when(promoBannerRepository.findById(1L)).thenReturn(Optional.of(banner));

        service.deactivate(1L);

        assertFalse(banner.getIsActive());
        verify(promoBannerRepository).save(banner);
    }

    @Test
    void deactivate_throwsWhenNotFound() {
        when(promoBannerRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.deactivate(99L));
    }
}