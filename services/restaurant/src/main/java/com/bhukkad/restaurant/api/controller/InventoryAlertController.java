package com.bhukkad.restaurant.api.controller;

import com.bhukkad.restaurant.domain.entity.InventoryAlert;
import com.bhukkad.restaurant.domain.service.impl.InventoryAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory/alerts")
@RequiredArgsConstructor
public class InventoryAlertController {

    private final InventoryAlertService alertService;

    @PostMapping
    public InventoryAlert raise(@RequestParam Long menuItemId, @RequestParam int currentStock,
                                @RequestParam int threshold) {
        return alertService.raise(menuItemId, currentStock, threshold);
    }

    @GetMapping
    public List<InventoryAlert> recent(@RequestParam Long menuItemId) {
        return alertService.recent(menuItemId);
    }
}