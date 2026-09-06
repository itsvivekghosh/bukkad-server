package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.service.inventory.StockReservationProperties;
import com.bhukkad.restaurant.service.inventory.StockReservationService;
import com.bhukkad.restaurant.service.inventory.StockReservationItem;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/inventory/stock-reservation")
@RequiredArgsConstructor
public class InventoryStockReservationController {

    private final StockReservationService stockReservationService;
    private final StockReservationProperties properties;

    @GetMapping("/enabled")
    public ResponseEntity<Map<String, Boolean>> isEnabled() {
        return ResponseEntity.ok(Map.of("enabled", stockReservationService.isEnabled()));
    }

    @PostMapping("/reserve")
    public ResponseEntity<Void> reserveStock(@RequestBody List<StockReservationItem> items) {
        stockReservationService.reserveStock(items);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/sync/{menuItemId}")
    public ResponseEntity<Void> syncStock(@PathVariable Long menuItemId) {
        stockReservationService.syncStock(menuItemId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/release")
    public ResponseEntity<Void> releaseStock(@RequestBody List<StockReservationItem> items) {
        stockReservationService.releaseStock(items);
        return ResponseEntity.ok().build();
    }
}
