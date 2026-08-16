package com.bhukkad.feed;

import com.bhukkad.dto.response.PromoBannerResponse;
import com.bhukkad.entity.PromoBanner;
import com.bhukkad.repository.PromoBannerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Serves active promotional banners for the home feed.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromoBannerService {

    private final PromoBannerRepository promoBannerRepository;

    /**
     * Lists currently active promo banners ordered for display.
     *
     * @return active banners
     */
    public List<PromoBannerResponse> listActive() {
        return promoBannerRepository.findActiveBanners(LocalDateTime.now()).stream()
                .map(this::toResponse)
                .toList();
    }

    private PromoBannerResponse toResponse(PromoBanner banner) {
        return PromoBannerResponse.builder()
                .id(banner.getId())
                .title(banner.getTitle())
                .subtitle(banner.getSubtitle())
                .imageUrl(banner.getImageUrl())
                .actionType(banner.getActionType())
                .actionTarget(banner.getActionTarget())
                .displayOrder(banner.getDisplayOrder())
                .build();
    }
}
