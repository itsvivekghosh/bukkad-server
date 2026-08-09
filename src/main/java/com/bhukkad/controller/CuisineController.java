package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.entity.Cuisine;
import com.bhukkad.repository.CuisineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cuisines")
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
                .orElseThrow(() -> new RuntimeException("Cuisine not found"));
        return ResponseEntity.ok(ApiResponse.success(cuisine));
    }
}