package com.bhukkad.feed;

import com.bhukkad.dto.request.PromoBannerRequest;
import com.bhukkad.dto.response.PromoBannerResponse;
import com.bhukkad.entity.PromoBanner;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.PromoBannerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin CRUD for home feed promo banners (V15).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromoBannerAdminService {

    private final PromoBannerRepository promoBannerRepository;

    public List<PromoBannerResponse> listAll() {
        return promoBannerRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public PromoBannerResponse create(PromoBannerRequest request) {
        PromoBanner banner = new PromoBanner();
        applyRequest(banner, request);
        return toResponse(promoBannerRepository.save(banner));
    }

    @Transactional
    public PromoBannerResponse update(Long id, PromoBannerRequest request) {
        PromoBanner banner = findOrThrow(id);
        applyRequest(banner, request);
        return toResponse(promoBannerRepository.save(banner));
    }

    @Transactional
    public void deactivate(Long id) {
        PromoBanner banner = findOrThrow(id);
        banner.setIsActive(false);
        promoBannerRepository.save(banner);
    }

    private PromoBanner findOrThrow(Long id) {
        return promoBannerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promo banner not found"));
    }

    private void applyRequest(PromoBanner banner, PromoBannerRequest request) {
        if (request.getTitle() != null) banner.setTitle(request.getTitle());
        if (request.getSubtitle() != null) banner.setSubtitle(request.getSubtitle());
        if (request.getImageUrl() != null) banner.setImageUrl(request.getImageUrl());
        if (request.getActionType() != null) banner.setActionType(request.getActionType());
        if (request.getActionTarget() != null) banner.setActionTarget(request.getActionTarget());
        if (request.getDisplayOrder() != null) banner.setDisplayOrder(request.getDisplayOrder());
        if (request.getIsActive() != null) banner.setIsActive(request.getIsActive());
        if (request.getStartsAt() != null) banner.setStartsAt(request.getStartsAt());
        if (request.getEndsAt() != null) banner.setEndsAt(request.getEndsAt());
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
