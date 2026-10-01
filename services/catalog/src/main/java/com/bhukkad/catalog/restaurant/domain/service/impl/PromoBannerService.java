package com.bhukkad.catalog.restaurant.domain.service.impl;

import com.bhukkad.catalog.restaurant.domain.entity.PromoBanner;
import com.bhukkad.catalog.restaurant.domain.repository.PromoBannerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromoBannerService {

    private final PromoBannerRepository promoBannerRepository;

    public List<PromoBanner> listActive() {
        return promoBannerRepository.findByActiveTrue();
    }
}
