package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.entity.Cuisine;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CuisineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/cuisines")
@RequiredArgsConstructor
public class CuisineController {

    private final CuisineRepository cuisineRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Cuisine>>> getAllCuisines() {
        List<Cuisine> cuisines = cuisineRepository.findByActiveTrue();
        return ResponseEntity.ok(ApiResponse.success(cuisines));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Cuisine>> getCuisineById(@PathVariable Long id) {
        Cuisine cuisine = cuisineRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cuisine not found"));
        return ResponseEntity.ok(ApiResponse.success(cuisine));
    }
}