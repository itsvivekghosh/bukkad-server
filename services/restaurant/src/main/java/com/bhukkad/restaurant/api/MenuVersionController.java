package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.domain.MenuVersion;
import com.bhukkad.restaurant.service.MenuVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/menu/versions")
@RequiredArgsConstructor
public class MenuVersionController {

    private final MenuVersionService menuVersionService;

    @PostMapping
    public MenuVersion snapshot(@RequestParam Long restaurantId, @RequestBody Map<String, Object> menu) {
        return menuVersionService.snapshot(restaurantId, menu);
    }

    @GetMapping
    public List<MenuVersion> history(@RequestParam Long restaurantId) {
        return menuVersionService.history(restaurantId);
    }
}