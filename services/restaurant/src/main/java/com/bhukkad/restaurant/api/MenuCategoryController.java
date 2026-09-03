package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.domain.MenuCategory;
import com.bhukkad.restaurant.service.MenuCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Menu category endpoints (Batch 4 wave 2 migration).
 *
 * <p>Restaurant-service port of the category surface of the monolith
 * {@code com.bhukkad.controller.MenuController}. Ownership checks live behind
 * the gateway in the monolith; here the surface is admin/service-facing. The
 * monolith keeps its working copy until the gateway flips.
 */
@RestController
@RequestMapping("/api/v1/restaurants/categories")
@RequiredArgsConstructor
public class MenuCategoryController {

    private final MenuCategoryService menuCategoryService;

    @PostMapping
    public ResponseEntity<ApiResponse<MenuCategory>> create(
            @RequestParam Long restaurantId, @RequestBody MenuCategory category) {
        return ResponseEntity.ok(ApiResponse.success("Category created",
                menuCategoryService.create(restaurantId, category)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MenuCategory>>> list(@RequestParam Long restaurantId) {
        return ResponseEntity.ok(ApiResponse.success(menuCategoryService.listByRestaurant(restaurantId)));
    }

    @PutMapping("/{categoryId}")
    public ResponseEntity<ApiResponse<MenuCategory>> update(
            @PathVariable Long categoryId, @RequestBody MenuCategory patch) {
        return ResponseEntity.ok(ApiResponse.success("Category updated",
                menuCategoryService.update(categoryId, patch)));
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long categoryId) {
        menuCategoryService.delete(categoryId);
        return ResponseEntity.ok(ApiResponse.success("Category deleted", null));
    }
}
