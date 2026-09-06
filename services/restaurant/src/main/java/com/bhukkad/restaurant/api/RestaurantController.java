package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.dto.request.RestaurantBusyModeRequest;
import com.bhukkad.restaurant.service.RestaurantAdminService;
import com.bhukkad.restaurant.service.RestaurantBusyService;
import com.bhukkad.restaurant.service.RestaurantDashboardService;
import com.bhukkad.restaurant.service.RestaurantQueryService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Public restaurant browse + write API ({@code /api/v1/restaurants}).
 */
@RestController
@RequestMapping("/api/v1/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantQueryService queryService;
    private final RestaurantAdminService adminService;
    private final RestaurantBusyService busyService;
    private final RestaurantDashboardService dashboardService;

    public record CreateRestaurantRequest(
            @NotBlank String name,
            String description,
            @NotNull Long cuisineId,
            String address,
            String phone) {}

    @PostMapping
    public Restaurant create(@RequestBody CreateRestaurantRequest request) {
        return adminService.create(request.name(), request.description(), request.cuisineId(),
                request.address(), request.phone());
    }

    @GetMapping
    public List<RestaurantSummary> browse(@RequestParam(required = false) Long cuisineId,
                                          @RequestParam(required = false) String name) {
        if (name != null && !name.isBlank()) {
            return queryService.search(name.trim());
        }
        if (cuisineId != null) {
            return queryService.browseByCuisine(cuisineId);
        }
        return List.of();
    }

    @GetMapping("/{restaurantId}/menu")
    public MenuSnapshot menu(@PathVariable Long restaurantId) {
        return queryService.menuSnapshot(restaurantId);
    }

    // Owner endpoints for busy mode and dashboard
    @PutMapping("/owner/{id}/busy-mode")
    public ResponseEntity<Void> enableBusyMode(
            @PathVariable Long id,
            @RequestBody RestaurantBusyModeRequest request) {
        java.time.LocalDateTime busyUntil = request.getBusyUntil();
        Integer extraPrepMinutes = request.getExtraPrepMinutes();
        busyService.setBusyMode(id, busyUntil, extraPrepMinutes);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/owner/{id}/busy-mode")
    public ResponseEntity<Void> disableBusyMode(@PathVariable Long id) {
        busyService.clearBusyMode(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/owner/{id}/dashboard")
    public ResponseEntity<RestaurantDashboardService.RestaurantDashboardView> getDashboard(
            @PathVariable Long id) {
        return ResponseEntity.ok(dashboardService.getDashboard(id));
    }
}
