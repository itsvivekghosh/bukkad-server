package com.bhukkad.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import com.bhukkad.restaurant.domain.entity.PromoBanner;

public interface PromoBannerRepository extends JpaRepository<PromoBanner, Long> {
    List<PromoBanner> findByActiveTrue();
}