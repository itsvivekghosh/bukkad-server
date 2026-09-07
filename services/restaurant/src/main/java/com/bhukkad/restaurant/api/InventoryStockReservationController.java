package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.service.inventory.StockReservationProperties;
import com.bhukkad.restaurant.service.inventory.StockReservationService;
import com.bhukkad.restaurant.service.inventory.StockReservationItem;
import lombok.AllArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    /** Reservation line at the API boundary — validation lives here (arch rule). */
    public record ReserveLine(
            @NotNull Long menuItemId,
            String menuItemName,
            @NotNull @Positive Integer quantity) {
        public StockReservationItem toServiceItem() {
            return new StockReservationItem(menuItemId, menuItemName, quantity);
        }
    }

    /**
     * Reservation is machine-to-machine only: the order saga (and future
     * checkout services) hold/restore stock. A customer JWT must never move
     * inventory — previously any authenticated user could release (inflate)
     * stock of any restaurant.
     */
    @PostMapping("/reserve")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Void> reserveStock(
            @RequestBody @NotEmpty(message = "items must not be empty") @Valid
            List<ReserveLine> items) {
        stockReservationService.reserveStock(items.stream().map(ReserveLine::toServiceItem).toList());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/sync/{menuItemId}")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public ResponseEntity<Void> syncStock(@PathVariable @Positive Long menuItemId) {
        stockReservationService.syncStock(menuItemId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/release")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Void> releaseStock(
            @RequestBody @NotEmpty(message = "items must not be empty") @Valid
            List<ReserveLine> items) {
        stockReservationService.releaseStock(items.stream().map(ReserveLine::toServiceItem).toList());
        return ResponseEntity.ok().build();
    }
}
