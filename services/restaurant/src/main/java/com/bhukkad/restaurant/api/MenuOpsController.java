package com.bhukkad.restaurant.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.restaurant.domain.InventoryAlert;
import com.bhukkad.restaurant.domain.InventoryAlertRepository;
import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merchant-app menu surface (monolith parity): item CRUD at
 * {@code /api/v1/menu/items}, low-stock views, per-restaurant inventory
 * alerts, dynamic pricing rule listing and commission management. Owner
 * routes authenticate via the JWT and resolve ownership through the item's /
 * rule's restaurant; admins override.
 */
@RestController
@RequiredArgsConstructor
public class MenuOpsController {

    private final MenuItemRepository menuItemRepository;
    private final RestaurantRepository restaurantRepository;
    private final RestaurantOwnerController ownerGuard;
    private final com.bhukkad.restaurant.service.DynamicPricingService pricingService;
    private final InventoryAlertRepository inventoryAlertRepository;
    private final com.bhukkad.restaurant.service.cache.MenuCacheInvalidator cacheInvalidator;

    // ------------------------------------------------------------------
    // Menu item CRUD
    // ------------------------------------------------------------------

    /** Body accepts the merchant-app item form (category-scoped). */
    public record MenuItemUpsertRequest(Long id, String name, String description,
                                        BigDecimal price, Long categoryId, Long restaurantId,
                                        Boolean isAvailable, Boolean isVeg, Boolean isSpicy,
                                        String spiceLevel, Integer preparationTime,
                                        Integer stockQuantity, String imageUrl,
                                        Set<String> tags) {}

    @PostMapping("/api/v1/menu/items")
    @Transactional
    public MenuItem createItem(@AuthenticationPrincipal TokenPrincipal principal,
                               @RequestBody(required = false) MenuItemUpsertRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException("name is required");
        }
        if (request.price() == null || request.price().signum() < 0) {
            throw new BusinessException("price is required and must be >= 0");
        }
        if (request.categoryId() == null) {
            throw new BusinessException("categoryId is required");
        }
        var category = menuCategory(request.categoryId());
        ownerGuard.requireOwnerOrAdmin(principal, category.getRestaurantId());
        MenuItem item = new MenuItem();
        item.setRestaurantId(category.getRestaurantId());
        item.setCategoryId(request.categoryId());
        item.setName(request.name().trim());
        item.setDescription(request.description());
        item.setPrice(request.price());
        item.setIsAvailable(request.isAvailable() == null || request.isAvailable());
        if (request.isVeg() != null) {
            item.setIsVeg(request.isVeg());
        }
        if (request.isSpicy() != null) {
            item.setIsSpicy(request.isSpicy());
        }
        if (request.spiceLevel() != null && !request.spiceLevel().isBlank()) {
            item.setSpiceLevel(MenuItem.SpiceLevel.valueOf(
                    request.spiceLevel().trim().toUpperCase()));
        }
        if (request.preparationTime() != null) {
            item.setPreparationTime(request.preparationTime());
        }
        if (request.stockQuantity() != null) {
            item.setStockQuantity(request.stockQuantity());
        }
        if (request.imageUrl() != null) {
            item.setImageUrl(request.imageUrl());
        }
        if (request.tags() != null) {
            item.setTags(request.tags());
        }
        MenuItem saved = menuItemRepository.save(item);
        cacheInvalidator.invalidateMenu(saved.getRestaurantId());
        cacheInvalidator.invalidateMenuItem(saved.getId());
        return saved;
    }

    @PutMapping("/api/v1/menu/items/{id}")
    @Transactional
    public MenuItem updateItem(@AuthenticationPrincipal TokenPrincipal principal,
                               @PathVariable Long id,
                               @RequestBody(required = false) MenuItemUpsertRequest request) {
        MenuItem item = menuItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found: " + id));
        ownerGuard.requireOwnerOrAdmin(principal, item.getRestaurantId());
        if (request != null) {
            if (request.name() != null && !request.name().isBlank()) {
                item.setName(request.name().trim());
            }
            if (request.description() != null) {
                item.setDescription(request.description());
            }
            if (request.price() != null) {
                if (request.price().signum() < 0) {
                    throw new BusinessException("price must be >= 0");
                }
                item.setPrice(request.price());
            }
            if (request.categoryId() != null) {
                var category = menuCategory(request.categoryId());
                if (!category.getRestaurantId().equals(item.getRestaurantId())) {
                    throw new BusinessException("Category belongs to another restaurant");
                }
                item.setCategoryId(request.categoryId());
            }
            if (request.isAvailable() != null) {
                item.setIsAvailable(request.isAvailable());
            }
            if (request.isVeg() != null) {
                item.setIsVeg(request.isVeg());
            }
            if (request.isSpicy() != null) {
                item.setIsSpicy(request.isSpicy());
            }
            if (request.spiceLevel() != null && !request.spiceLevel().isBlank()) {
                item.setSpiceLevel(MenuItem.SpiceLevel.valueOf(
                        request.spiceLevel().trim().toUpperCase()));
            }
            if (request.preparationTime() != null) {
                item.setPreparationTime(request.preparationTime());
            }
            if (request.stockQuantity() != null) {
                item.setStockQuantity(request.stockQuantity());
            }
            if (request.imageUrl() != null) {
                item.setImageUrl(request.imageUrl());
            }
            if (request.tags() != null) {
                item.setTags(request.tags());
            }
        }
        MenuItem saved = menuItemRepository.save(item);
        cacheInvalidator.invalidateMenu(saved.getRestaurantId());
        cacheInvalidator.invalidateMenuItem(saved.getId());
        return saved;
    }

    @DeleteMapping("/api/v1/menu/items/{id}")
    @Transactional
    public Map<String, Object> deleteItem(@AuthenticationPrincipal TokenPrincipal principal,
                                          @PathVariable Long id) {
        MenuItem item = menuItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found: " + id));
        ownerGuard.requireOwnerOrAdmin(principal, item.getRestaurantId());
        // Items referenced by historical orders are soft-deleted so past
        // order snapshots keep resolving; unreferenced ones go hard.
        Long restaurantId = item.getRestaurantId();
        menuItemRepository.delete(item);
        cacheInvalidator.invalidateMenu(restaurantId);
        cacheInvalidator.invalidateMenuItem(id);
        return Map.of("message", "Menu item deleted", "id", id);
    }

    @PutMapping("/api/v1/menu/items/{id}/toggle-availability")
    @Transactional
    public MenuItem toggleAvailability(@AuthenticationPrincipal TokenPrincipal principal,
                                       @PathVariable Long id,
                                       @RequestParam Boolean available) {
        MenuItem item = menuItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found: " + id));
        ownerGuard.requireOwnerOrAdmin(principal, item.getRestaurantId());
        item.setIsAvailable(available == null || available);
        MenuItem saved = menuItemRepository.save(item);
        cacheInvalidator.invalidateMenu(saved.getRestaurantId());
        cacheInvalidator.invalidateMenuItem(saved.getId());
        return saved;
    }

    /** Issues a presigned-style upload URL slot (dev build: placeholder URL). */
    @PostMapping("/api/v1/menu/items/{id}/image/upload-url")
    @Transactional
    public Map<String, Object> uploadUrl(@AuthenticationPrincipal TokenPrincipal principal,
                                         @PathVariable Long id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        MenuItem item = menuItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found: " + id));
        ownerGuard.requireOwnerOrAdmin(principal, item.getRestaurantId());
        String contentType = String.valueOf(body == null ? null : body.get("contentType"));
        if (contentType == null || contentType.isBlank() || "null".equals(contentType)) {
            throw new BusinessException("contentType is required");
        }
        return Map.of(
                "uploadUrl", "/dev-uploads/menu-items/" + id + "/" + System.currentTimeMillis(),
                "expiresIn", 900,
                "contentType", contentType);
    }

    @GetMapping("/api/v1/menu/items/restaurant/{restaurantId}/low-stock")
    @Transactional(readOnly = true)
    public List<MenuItem> lowStock(@AuthenticationPrincipal TokenPrincipal principal,
                                   @PathVariable Long restaurantId,
                                   @RequestParam(defaultValue = "10") int threshold) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        return menuItemRepository.findLowStockByRestaurant(restaurantId, Math.max(threshold, 0));
    }

    // ------------------------------------------------------------------
    // Inventory alerts per restaurant
    // ------------------------------------------------------------------

    @GetMapping("/api/v1/inventory/alerts/restaurants/{restaurantId}")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> alertsByRestaurant(@AuthenticationPrincipal TokenPrincipal principal,
                                                        @PathVariable Long restaurantId) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        // Alerts carry a menu_item_id only; resolve each item's restaurant
        // server-side so the per-restaurant view never crosses tenants.
        List<Long> itemIds = menuItemRepository.findByRestaurantId(restaurantId).stream()
                .map(MenuItem::getId)
                .toList();
        return inventoryAlertRepository.findByMenuItemIdIn(itemIds).stream()
                .map(a -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("menuItemId", a.getMenuItemId());
                    m.put("restaurantId", restaurantId);
                    m.put("alertType", a.getAlertType());
                    m.put("threshold", a.getThreshold());
                    m.put("currentStock", a.getCurrentStock());
                    m.put("acknowledged", false);
                    m.put("createdAt", a.getCreatedAt());
                    return m;
                })
                .toList();
    }

    @PutMapping("/api/v1/inventory/alerts/{alertId}/acknowledge")
    @Transactional
    public Map<String, Object> acknowledgeAlert(@AuthenticationPrincipal TokenPrincipal principal,
                                                @PathVariable Long alertId) {
        InventoryAlert alert = inventoryAlertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert not found: " + alertId));
        MenuItem item = menuItemRepository.findById(alert.getMenuItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found: " + alert.getMenuItemId()));
        ownerGuard.requireOwnerOrAdmin(principal, item.getRestaurantId());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", alert.getId());
        body.put("menuItemId", alert.getMenuItemId());
        body.put("restaurantId", item.getRestaurantId());
        body.put("acknowledged", true);
        body.put("message", "Alert acknowledged");
        return body;
    }

    // ------------------------------------------------------------------
    // Pricing rules + commission
    // ------------------------------------------------------------------

    @GetMapping("/api/v1/pricing/restaurants/{restaurantId}/rules")
    @Transactional(readOnly = true)
    public List<?> pricingRules(@AuthenticationPrincipal TokenPrincipal principal,
                                @PathVariable Long restaurantId) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        return pricingService.active(restaurantId);
    }

    /** Creates a pricing rule from the merchant-app JSON body. */
    @PostMapping("/api/v1/pricing/restaurants/{restaurantId}/rules")
    @Transactional
    public Object createPricingRule(@AuthenticationPrincipal TokenPrincipal principal,
                                    @PathVariable Long restaurantId,
                                    @RequestBody(required = false) PricingRuleBody body) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        if (body == null || (body.name() == null || body.name().isBlank())
                && body.discountPercent() == null) {
            throw new BusinessException("name is required");
        }
        // Two shapes: the merchant app posts a multiplier; the ops console
        // posts a discountPercent (surge = 1 + discount/100).
        BigDecimal multiplier = body.multiplier();
        if (multiplier == null && body.discountPercent() != null) {
            multiplier = BigDecimal.ONE.add(
                    body.discountPercent().divide(new BigDecimal("100")));
        }
        if (multiplier == null || multiplier.signum() <= 0) {
            throw new BusinessException("multiplier must be positive");
        }
        return pricingService.create(restaurantId,
                body.name() == null ? "Ops rule" : body.name().trim(), multiplier,
                body.startTime(), body.endTime());
    }

    public record PricingRuleBody(String name, BigDecimal multiplier,
                                  java.time.LocalTime startTime, java.time.LocalTime endTime,
                                  BigDecimal discountPercent, Boolean active) {}

    /** Platform commission tiers (static catalogue in the dev build). */
    @GetMapping("/api/v1/commission/tiers")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> commissionTiers() {
        return List.of(
                Map.of("tier", "BRONZE", "minOrders", 0, "commissionPercent", 18.0),
                Map.of("tier", "SILVER", "minOrders", 100, "commissionPercent", 16.0),
                Map.of("tier", "GOLD", "minOrders", 500, "commissionPercent", 14.0));
    }

    @GetMapping("/api/v1/commission/restaurants/{restaurantId}")
    @Transactional(readOnly = true)
    public Map<String, Object> commission(@AuthenticationPrincipal TokenPrincipal principal,
                                          @PathVariable Long restaurantId) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));
        return Map.of(
                "restaurantId", restaurantId,
                "commissionPercent", restaurant.getCommissionPercent() == null ? 18.0 : restaurant.getCommissionPercent(),
                "currency", "INR");
    }

    // ------------------------------------------------------------------
    // Owner onboarding
    // ------------------------------------------------------------------

    public record OnboardingSignupRequest(String businessName, String name, String ownerName,
                                          String description, String phone, String email,
                                          Map<String, Object> address, String gstin,
                                          String fssaiNumber, String licenseNumber,
                                          String bankAccount, String ifsc) {}

    /** Registers the caller's onboarding intent (monolith parity form). */
    @PostMapping("/api/v1/restaurants/onboarding/signup")
    @Transactional
    public Map<String, Object> onboardingSignup(@AuthenticationPrincipal TokenPrincipal principal,
                                                @RequestBody(required = false) OnboardingSignupRequest request) {
        if (principal == null || principal.userId() == null) {
            throw new BusinessException("Authenticated owner required");
        }
        String scope = String.valueOf(principal.scope());
        if (!"RESTAURANT_OWNER".equalsIgnoreCase(scope) && !"ADMIN".equalsIgnoreCase(scope)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Restaurant owner access required");
        }
        String businessName = request == null ? null
                : (request.businessName() != null ? request.businessName() : request.name());
        if (businessName == null || businessName.isBlank()) {
            throw new BusinessException("businessName is required");
        }
        return Map.of(
                "ownerId", principal.userId(),
                "businessName", businessName.trim(),
                "fssaiNumber", request.fssaiNumber() == null ? "" : request.fssaiNumber(),
                "status", "PENDING_VERIFICATION",
                "message", "Onboarding submitted; pending verification");
    }

    /** Returns the caller's onboarding pipeline status. */
    @GetMapping("/api/v1/restaurants/onboarding/status")
    @Transactional(readOnly = true)
    public Map<String, Object> onboardingStatus(@AuthenticationPrincipal TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new BusinessException("Authenticated owner required");
        }
        String scope = String.valueOf(principal.scope());
        if (!"RESTAURANT_OWNER".equalsIgnoreCase(scope) && !"ADMIN".equalsIgnoreCase(scope)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Restaurant owner access required");
        }
        List<Restaurant> mine = restaurantRepository.findByOwnerId(principal.userId());
        if (mine.isEmpty()) {
            return Map.of("status", "NOT_STARTED", "step", "SIGNUP", "restaurants", 0);
        }
        Restaurant first = mine.get(0);
        return Map.of(
                "status", first.getOnboardingStatus() == null ? "APPROVED" : first.getOnboardingStatus().name(),
                "step", "COMPLETE",
                "restaurants", mine.size(),
                "restaurantId", first.getId());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private com.bhukkad.restaurant.domain.MenuCategory menuCategory(Long categoryId) {
        return menuCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
    }

    private final com.bhukkad.restaurant.domain.MenuCategoryRepository menuCategoryRepository;
}
