package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.domain.Cuisine;
import com.bhukkad.restaurant.service.CuisineService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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