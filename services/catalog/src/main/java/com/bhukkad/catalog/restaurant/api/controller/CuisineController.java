package com.bhukkad.catalog.restaurant.api.controller;

import com.bhukkad.catalog.restaurant.domain.entity.Cuisine;
import com.bhukkad.catalog.restaurant.domain.service.impl.CuisineService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.bhukkad.catalog.restaurant.api.dto.response.ApiResponse;

@RestController
@RequestMapping("/api/v1/cuisines")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
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

    /**
     * Cuisine taxonomy is admin-managed. The security config only permits GET
     * {@code /api/v1/cuisines/**} publicly — writes fell through to plain
     * {@code authenticated()}, so ANY signed-in customer could mint cuisines.
     * ADMIN-only matches the rest of the catalog admin surface.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Cuisine> create(@RequestParam @NotBlank String name) {
        return ApiResponse.success(cuisineService.create(name));
    }
}