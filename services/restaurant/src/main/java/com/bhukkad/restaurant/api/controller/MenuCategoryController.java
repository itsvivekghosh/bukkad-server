package com.bhukkad.restaurant.api.controller;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.restaurant.api.dto.response.ApiResponse;
import com.bhukkad.restaurant.domain.entity.MenuCategory;
import com.bhukkad.restaurant.domain.service.impl.MenuCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Menu category CRUD. Mutations are owner-or-admin on the addressed
 * restaurant: without the guard any authenticated user could rename, hide,
 * or delete any restaurant's categories.
 */
@RestController
@RequestMapping("/api/v1/restaurants/categories")
@RequiredArgsConstructor
public class MenuCategoryController {

    private final MenuCategoryService menuCategoryService;
    private final RestaurantOwnerController ownerGuard;

    @PostMapping
    public ResponseEntity<ApiResponse<MenuCategory>> create(
            @AuthenticationPrincipal TokenPrincipal principal,
            @RequestParam Long restaurantId,
            @RequestBody MenuCategory category) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        return ResponseEntity.ok(ApiResponse.success("Category created",
                menuCategoryService.create(restaurantId, category)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MenuCategory>>> list(@RequestParam Long restaurantId) {
        return ResponseEntity.ok(ApiResponse.success(menuCategoryService.listByRestaurant(restaurantId)));
    }

    @PutMapping("/{categoryId}")
    public ResponseEntity<ApiResponse<MenuCategory>> update(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long categoryId,
            @RequestBody MenuCategory patch) {
        MenuCategory existing = menuCategoryService.get(categoryId);
        ownerGuard.requireOwnerOrAdmin(principal, existing.getRestaurantId());
        return ResponseEntity.ok(ApiResponse.success("Category updated",
                menuCategoryService.update(categoryId, patch)));
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long categoryId) {
        MenuCategory existing = menuCategoryService.get(categoryId);
        ownerGuard.requireOwnerOrAdmin(principal, existing.getRestaurantId());
        menuCategoryService.delete(categoryId);
        return ResponseEntity.ok(ApiResponse.success("Category deleted", null));
    }
}
