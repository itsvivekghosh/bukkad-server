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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final RestaurantOwnerController ownerGuard;

    public record CreateRestaurantRequest(
            @NotBlank String name,
            String description,
            @NotNull Long cuisineId,
            String address,
            String phone) {}

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Restaurant create(@org.springframework.validation.annotation.Validated @jakarta.validation.Valid
                             @RequestBody CreateRestaurantRequest request) {
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

    // Owner endpoints for busy mode and dashboard. Ownership enforced here
    // (defense in depth): the path id was previously trusted blindly, so any
    // customer could flip any restaurant's busy mode.
    @PutMapping("/owner/{id}/busy-mode")
    public ResponseEntity<Void> enableBusyMode(
            @AuthenticationPrincipal com.bhukkad.common.security.TokenPrincipal principal,
            @PathVariable Long id,
            @RequestBody RestaurantBusyModeRequest request) {
        ownerGuard.requireOwnerOrAdmin(principal, id);
        java.time.LocalDateTime busyUntil = request.getBusyUntil();
        Integer extraPrepMinutes = request.getExtraPrepMinutes();
        busyService.setBusyMode(id, busyUntil, extraPrepMinutes);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/owner/{id}/busy-mode")
    public ResponseEntity<Void> disableBusyMode(
            @AuthenticationPrincipal com.bhukkad.common.security.TokenPrincipal principal,
            @PathVariable Long id) {
        ownerGuard.requireOwnerOrAdmin(principal, id);
        busyService.clearBusyMode(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/owner/{id}/dashboard")
    public ResponseEntity<RestaurantDashboardService.RestaurantDashboardView> getDashboard(
            @AuthenticationPrincipal com.bhukkad.common.security.TokenPrincipal principal,
            @PathVariable Long id) {
        ownerGuard.requireOwnerOrAdmin(principal, id);
        return ResponseEntity.ok(dashboardService.getDashboard(id));
    }
}
