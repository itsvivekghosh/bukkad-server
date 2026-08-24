package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.BulkUploadReport;
import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/menu")
@RequiredArgsConstructor
@Tag(name = "Menu Bulk", description = "Bulk menu management endpoints")
public class MenuBulkController {

    private final MenuService menuService;
    private final SecurityUtils securityUtils;

    /**
     * Bulk upsert of menu items from a CSV file with columns
     * name,category,price,description,isVeg,isJain,availableFrom,availableUntil,active.
     * Rows are validated individually; per-row errors are returned in the report.
     */
    @PostMapping(value = "/items/bulk-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Bulk upload menu items via CSV")
    public ResponseEntity<ApiResponse<BulkUploadReport>> bulkUpload(
            @RequestParam Long restaurantId,
            @RequestParam("file") MultipartFile file) {
        BulkUploadReport report = menuService.bulkUploadCsv(file, restaurantId, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success("Bulk upload processed", report));
    }

    /**
     * Diet-filtered menu listing. The {@code diet} param is required here so
     * this handler only matches requests carrying it; the plain listing at the
     * same path is served by {@link MenuController} ({@code params = "!diet"}).
     */
    @GetMapping(value = "/items/restaurant/{restaurantId}", params = "diet")
    @Operation(summary = "Get menu items by restaurant with dietary filter")
    public ResponseEntity<ApiResponse<List<MenuItemResponse>>> getMenuItemsByRestaurant(
            @PathVariable Long restaurantId,
            @RequestParam String diet) {
        List<MenuItemResponse> items = menuService.getMenuItemsByRestaurant(restaurantId, diet);
        return ResponseEntity.ok(ApiResponse.success(items));
    }
}
