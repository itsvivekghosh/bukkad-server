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

    @GetMapping
    public List<Cuisine> all() {
        return cuisineService.all();
    }

    @PostMapping
    public Cuisine create(@RequestParam @NotBlank String name) {
        return cuisineService.create(name);
    }
}