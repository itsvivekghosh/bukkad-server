package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.cache.http.HttpCacheSupport;
import com.bhukkad.dto.request.MenuImageUploadRequest;
import com.bhukkad.dto.request.MenuCategoryRequest;
import com.bhukkad.dto.request.MenuItemRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.MenuCategoryResponse;
import com.bhukkad.dto.response.MenuImageUploadResponse;
import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.ratelimit.RateLimited;
import com.bhukkad.service.MenuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;
    private final HttpCacheSupport httpCacheSupport;

    // Category endpoints
    @PostMapping("/categories")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<MenuCategoryResponse>> createCategory(
            @RequestParam Long restaurantId,
            @Valid @RequestBody MenuCategoryRequest category) {
        MenuCategoryResponse createdCategory = menuService.createCategory(restaurantId, category);
        return ResponseEntity.ok(ApiResponse.success("Category created successfully", createdCategory));
    }

    @GetMapping("/categories/restaurant/{restaurantId}")
    public ResponseEntity<ApiResponse<List<MenuCategoryResponse>>> getCategoriesByRestaurant(
            @PathVariable Long restaurantId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<MenuCategoryResponse> categories = menuService.getCategoriesByRestaurant(restaurantId);
        ApiResponse<List<MenuCategoryResponse>> body = ApiResponse.success(categories);

        HttpHeaders headers = httpCacheSupport.buildCacheHeaders(
                com.bhukkad.cache.CacheKeyGenerator.menuCategoriesByRestaurant(restaurantId),
                body.toString());
        String etag = headers.getETag();

        if (httpCacheSupport.isNotModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(304).build();
        }

        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PutMapping("/categories/{categoryId}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<MenuCategoryResponse>> updateCategory(
            @PathVariable Long categoryId,
            @Valid @RequestBody MenuCategoryRequest category) {
        MenuCategoryResponse updatedCategory = menuService.updateCategory(categoryId, category);
        return ResponseEntity.ok(ApiResponse.success("Category updated successfully", updatedCategory));
    }

    @DeleteMapping("/categories/{categoryId}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<Void>> deleteCategory(@PathVariable Long categoryId) {
        menuService.deleteCategory(categoryId);
        return ResponseEntity.ok(ApiResponse.success("Category deleted successfully", null));
    }

    // Menu item endpoints
    @PostMapping("/items")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<MenuItemResponse>> createMenuItem(@Valid @RequestBody MenuItemRequest request) {
        MenuItemResponse menuItem = menuService.createMenuItem(request);
        return ResponseEntity.ok(ApiResponse.success("Menu item created successfully", menuItem));
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<ApiResponse<MenuItemResponse>> getMenuItemById(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        MenuItemResponse menuItem = menuService.getMenuItemById(id);
        ApiResponse<MenuItemResponse> body = ApiResponse.success(menuItem);

        HttpHeaders headers = httpCacheSupport.buildCacheHeaders(
                com.bhukkad.cache.CacheKeyGenerator.menuItem(id),
                body.toString());
        String etag = headers.getETag();

        if (httpCacheSupport.isNotModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(304).build();
        }

        return ResponseEntity.ok().headers(headers).body(body);
    }

    @GetMapping("/items/category/{categoryId}")
    public ResponseEntity<ApiResponse<List<MenuItemResponse>>> getMenuItemsByCategory(
            @PathVariable Long categoryId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<MenuItemResponse> menuItems = menuService.getMenuItemsByCategory(categoryId);
        ApiResponse<List<MenuItemResponse>> body = ApiResponse.success(menuItems);

        HttpHeaders headers = httpCacheSupport.buildCacheHeaders(
                com.bhukkad.cache.CacheKeyGenerator.menuItemsByCategory(categoryId),
                body.toString());
        String etag = headers.getETag();

        if (httpCacheSupport.isNotModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(304).build();
        }

        return ResponseEntity.ok().headers(headers).body(body);
    }

    @GetMapping("/items/restaurant/{restaurantId}")
    public ResponseEntity<ApiResponse<List<MenuItemResponse>>> getMenuItemsByRestaurant(
            @PathVariable Long restaurantId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<MenuItemResponse> menuItems = menuService.getMenuItemsByRestaurant(restaurantId);
        ApiResponse<List<MenuItemResponse>> body = ApiResponse.success(menuItems);

        HttpHeaders headers = httpCacheSupport.buildCacheHeaders(
                com.bhukkad.cache.CacheKeyGenerator.menuItemsByRestaurant(restaurantId),
                body.toString());
        String etag = headers.getETag();

        if (httpCacheSupport.isNotModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(304).build();
        }

        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PutMapping("/items/{id}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<MenuItemResponse>> updateMenuItem(
            @PathVariable Long id,
            @Valid @RequestBody MenuItemRequest request) {
        MenuItemResponse menuItem = menuService.updateMenuItem(id, request);
        return ResponseEntity.ok(ApiResponse.success("Menu item updated successfully", menuItem));
    }

    @DeleteMapping("/items/{id}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<Void>> deleteMenuItem(@PathVariable Long id) {
        menuService.deleteMenuItem(id);
        return ResponseEntity.ok(ApiResponse.success("Menu item deleted successfully", null));
    }

    @PutMapping("/items/{id}/toggle-availability")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<Void>> toggleItemAvailability(
            @PathVariable Long id,
            @RequestParam Boolean available) {
        menuService.toggleItemAvailability(id, available);
        return ResponseEntity.ok(ApiResponse.success("Item availability updated", null));
    }

    @GetMapping("/items/restaurant/{restaurantId}/bestsellers")
    public ResponseEntity<ApiResponse<List<MenuItemResponse>>> getBestsellers(@PathVariable Long restaurantId) {
        List<MenuItemResponse> bestsellers = menuService.getBestsellers(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(bestsellers));
    }

    @GetMapping("/items/restaurant/{restaurantId}/recommended")
    public ResponseEntity<ApiResponse<List<MenuItemResponse>>> getRecommended(@PathVariable Long restaurantId) {
        List<MenuItemResponse> recommended = menuService.getRecommended(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(recommended));
    }

    @PostMapping("/items/{id}/image/upload-url")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<MenuImageUploadResponse>> createMenuItemImageUploadUrl(
            @PathVariable Long id,
            @Valid @RequestBody MenuImageUploadRequest request) {
        MenuImageUploadResponse response = menuService.createMenuItemImageUploadUrl(id, request);
        return ResponseEntity.ok(ApiResponse.success("Upload URL created", response));
    }

    @GetMapping("/items/search")
    @RateLimited("search")
    public ResponseEntity<ApiResponse<List<MenuItemResponse>>> searchMenuItems(@RequestParam String keyword) {
        List<MenuItemResponse> menuItems = menuService.searchMenuItems(keyword);
        return ResponseEntity.ok(ApiResponse.success(menuItems));
    }

    @GetMapping("/items/restaurant/{restaurantId}/low-stock")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<List<MenuItemResponse>>> getLowStockItems(
            @PathVariable Long restaurantId,
            @RequestParam(required = false) Integer threshold) {
        List<MenuItemResponse> items = menuService.getLowStockItems(restaurantId, threshold);
        return ResponseEntity.ok(ApiResponse.success(items));
    }
}