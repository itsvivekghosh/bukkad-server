package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.domain.Cuisine;
import com.bhukkad.restaurant.domain.CuisineRepository;
import com.bhukkad.common.error.DuplicateRequestException;
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
    public List<Cuisine> all() {
        return cuisineRepository.findAll();
    }

    @Transactional
    public Cuisine create(String name) {
        if (cuisineRepository.findAll().stream().anyMatch(c -> c.getName().equalsIgnoreCase(name))) {
            throw new DuplicateRequestException("Cuisine already exists: " + name);
        }
        Cuisine cuisine = new Cuisine();
        cuisine.setName(name);
        return cuisineRepository.save(cuisine);
    }
}