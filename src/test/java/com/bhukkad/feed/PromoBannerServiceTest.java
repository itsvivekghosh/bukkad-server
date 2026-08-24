package com.bhukkad.feed;

import com.bhukkad.dto.response.PromoBannerResponse;
import com.bhukkad.entity.PromoBanner;
import com.bhukkad.repository.PromoBannerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromoBannerServiceTest {

    @Mock
    private PromoBannerRepository promoBannerRepository;

    @InjectMocks
    private PromoBannerService service;

    private PromoBanner banner(Long id, String title, Integer order) {
        PromoBanner b = new PromoBanner();
        b.setId(id);
        b.setTitle(title);
        b.setSubtitle("sub");
        b.setImageUrl("https://cdn/banner.jpg");
        b.setActionType("DEEP_LINK");
        b.setActionTarget("/offers");
        b.setDisplayOrder(order);
        return b;
    }

    @Test void listActive_returnsMappedBanners() {
        when(promoBannerRepository.findActiveBanners(any(LocalDateTime.class)))
                .thenReturn(List.of(banner(1L, "Offer", 1), banner(2L, "Weekend", 2)));

        List<PromoBannerResponse> banners = service.listActive();

        assertEquals(2, banners.size());
        assertEquals("Offer", banners.get(0).getTitle());
        assertEquals("DEEP_LINK", banners.get(0).getActionType());
        assertEquals("/offers", banners.get(0).getActionTarget());
        assertEquals(1, banners.get(0).getDisplayOrder());
    }

    @Test void listActive_empty_returnsEmptyList() {
        when(promoBannerRepository.findActiveBanners(any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertTrue(service.listActive().isEmpty());
    }

    @Test void listActive_preservesOrder() {
        when(promoBannerRepository.findActiveBanners(any(LocalDateTime.class)))
                .thenReturn(List.of(banner(1L, "A", 3), banner(2L, "B", 1)));

        List<PromoBannerResponse> banners = service.listActive();
        assertEquals(3, banners.get(0).getDisplayOrder());
        assertEquals(1, banners.get(1).getDisplayOrder());
    }
}