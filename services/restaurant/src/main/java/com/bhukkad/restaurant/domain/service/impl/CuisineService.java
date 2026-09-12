package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.restaurant.domain.entity.Cuisine;
import com.bhukkad.restaurant.domain.repository.CuisineRepository;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.scan.AllowFullScan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Cuisine catalog (port of monolith {@code CuisineService}).
 */
@Service
@RequiredArgsConstructor
public class CuisineService {

    private final CuisineRepository cuisineRepository;

    @Transactional(readOnly = true)
    @AllowFullScan(reason = "G-6 reviewed: cuisines are a small bounded reference table (hand-curated catalog entries)")
    public List<Cuisine> all() {
        return cuisineRepository.findAll();
    }

    @Transactional
    @AllowFullScan(reason = "G-6 reviewed: duplicate-name check over the small bounded cuisine reference table; replaceable by an existsByNameIgnoreCase query")
    public Cuisine create(String name) {
        if (cuisineRepository.findAll().stream().anyMatch(c -> c.getName().equalsIgnoreCase(name))) {
            throw new DuplicateRequestException("Cuisine already exists: " + name);
        }
        Cuisine cuisine = new Cuisine();
        cuisine.setName(name);
        return cuisineRepository.save(cuisine);
    }
}