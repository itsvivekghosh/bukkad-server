package com.bhukkad.restaurant.api.controller;

import com.bhukkad.restaurant.domain.entity.Cuisine;
import com.bhukkad.restaurant.domain.service.impl.CuisineService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.bhukkad.restaurant.api.dto.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/cuisines")
@RequiredArgsConstructor
public class CuisineController {

    private final CuisineService cuisineService;

    /**
     * Returns the cuisine list wrapped in the standard {@link ApiResponse}
     * envelope — identical to the monolith's {@code /api/v1/cuisines} contract
     * so flipping the gateway route never breaks clients.
     */
    @GetMapping
    public ApiResponse<List<Cuisine>> all() {
        return ApiResponse.success(cuisineService.all());
    }

    @PostMapping
    public ApiResponse<Cuisine> create(@RequestParam @NotBlank String name) {
        return ApiResponse.success(cuisineService.create(name));
    }
}