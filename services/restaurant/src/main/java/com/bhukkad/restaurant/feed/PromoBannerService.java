package com.bhukkad.restaurant.feed;

import com.bhukkad.restaurant.domain.PromoBanner;
import com.bhukkad.restaurant.domain.PromoBannerRepository;
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
