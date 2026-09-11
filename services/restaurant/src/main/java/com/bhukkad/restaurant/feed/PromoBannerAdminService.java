package com.bhukkad.restaurant.feed;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.scan.AllowFullScan;
import com.bhukkad.restaurant.domain.PromoBanner;
import com.bhukkad.restaurant.domain.PromoBannerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromoBannerAdminService {

    private final PromoBannerRepository promoBannerRepository;

    @AllowFullScan(reason = "G-6 reviewed: promo banners are a small bounded reference table (admin-managed homepage banners)")
    public List<PromoBanner> listAll() {
        return promoBannerRepository.findAll();
    }

    @Transactional
    public PromoBanner create(String title, String imageUrl, Boolean active) {
        PromoBanner banner = new PromoBanner();
        banner.setTitle(title);
        banner.setImageUrl(imageUrl);
        banner.setActive(active != null ? active : Boolean.TRUE);
        return promoBannerRepository.save(banner);
    }

    @Transactional
    public PromoBanner update(Long id, String title, String imageUrl, Boolean active) {
        PromoBanner banner = findOrThrow(id);
        if (title != null) banner.setTitle(title);
        if (imageUrl != null) banner.setImageUrl(imageUrl);
        if (active != null) banner.setActive(active);
        return promoBannerRepository.save(banner);
    }

    @Transactional
    public void deactivate(Long id) {
        PromoBanner banner = findOrThrow(id);
        banner.setActive(false);
        promoBannerRepository.save(banner);
    }

    private PromoBanner findOrThrow(Long id) {
        return promoBannerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promo banner not found: " + id));
    }
}
