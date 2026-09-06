package com.bhukkad.restaurant.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.restaurant.domain.MenuCategory;
import com.bhukkad.restaurant.domain.MenuCategoryRepository;
import com.bhukkad.restaurant.service.DynamicPricingService;
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

import java.util.List;
import java.util.Map;

/**
 * Monolith-parity aliases for the merchant app's menu-category and pricing
 * surfaces: category CRUD at {@code /api/v1/menu/categories} (canonical
 * categories live under {@code /api/v1/restaurants/categories}) and pricing
 * rule update / happy-hour views under {@code /api/v1/pricing}. Owner access
 * is resolved through the category's / rule's restaurant.
 */
@RestController
@RequiredArgsConstructor
public class MenuCategoryCompatController {

    private final MenuCategoryRepository menuCategoryRepository;
    private final com.bhukkad.restaurant.domain.MenuItemRepository menuItemRepository;
    private final RestaurantOwnerController ownerGuard;
    private final DynamicPricingService pricingService;

    public record CategoryUpsertRequest(String name, String description, Long restaurantId,
                                        Integer displayOrder, Boolean active) {}

    @PostMapping("/api/v1/menu/categories")
    @Transactional
    public MenuCategory createCategory(@AuthenticationPrincipal TokenPrincipal principal,
                                       @RequestParam Long restaurantId,
                                       @RequestBody(required = false) CategoryUpsertRequest request) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException("name is required");
        }
        MenuCategory category = new MenuCategory();
        category.setRestaurantId(restaurantId);
        category.setName(request.name().trim());
        category.setDescription(request.description());
        category.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
        category.setActive(request.active() == null || request.active());
        return menuCategoryRepository.save(category);
    }

    @PutMapping("/api/v1/menu/categories/{categoryId}")
    @Transactional
    public MenuCategory updateCategory(@AuthenticationPrincipal TokenPrincipal principal,
                                       @PathVariable Long categoryId,
                                       @RequestBody(required = false) CategoryUpsertRequest request) {
        MenuCategory category = menuCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
        ownerGuard.requireOwnerOrAdmin(principal, category.getRestaurantId());
        if (request != null) {
            if (request.name() != null && !request.name().isBlank()) {
                category.setName(request.name().trim());
            }
            if (request.description() != null) {
                category.setDescription(request.description());
            }
            if (request.displayOrder() != null) {
                category.setDisplayOrder(request.displayOrder());
            }
            if (request.active() != null) {
                category.setActive(request.active());
            }
        }
        return menuCategoryRepository.save(category);
    }

    @DeleteMapping("/api/v1/menu/categories/{categoryId}")
    @Transactional
    public Map<String, Object> deleteCategory(@AuthenticationPrincipal TokenPrincipal principal,
                                              @PathVariable Long categoryId) {
        MenuCategory category = menuCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
        ownerGuard.requireOwnerOrAdmin(principal, category.getRestaurantId());
        List<com.bhukkad.restaurant.domain.MenuItem> items =
                menuItemRepository.findByCategoryId(categoryId);
        if (!items.isEmpty()) {
            throw new BusinessException(
                    "Category has " + items.size() + " menu items; move or delete them first");
        }
        menuCategoryRepository.delete(category);
        return Map.of("message", "Category deleted", "id", categoryId);
    }

    // ------------------------------------------------------------------
    // Pricing rule update / happy-hour aliases
    // ------------------------------------------------------------------

    public record PricingRuleUpdateBody(String name, java.math.BigDecimal multiplier,
                                        java.time.LocalTime startTime,
                                        java.time.LocalTime endTime, Boolean active) {}

    @PutMapping("/api/v1/pricing/rules/{ruleId}")
    @Transactional
    public Object updatePricingRule(@AuthenticationPrincipal TokenPrincipal principal,
                                    @PathVariable Long ruleId,
                                    @RequestBody(required = false) PricingRuleUpdateBody body) {
        var rule = pricingService.getRule(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Pricing rule not found"));
        ownerGuard.requireOwnerOrAdmin(principal, rule.getRestaurantId());
        if (body == null) {
            return rule;
        }
        if (body.name() != null && !body.name().isBlank()) {
            rule.setRuleName(body.name().trim());
        }
        if (body.multiplier() != null) {
            if (body.multiplier().compareTo(new java.math.BigDecimal("1.0")) < 0
                    || body.multiplier().compareTo(new java.math.BigDecimal("3.0")) > 0) {
                throw new BusinessException("multiplier must be between 1.0 and 3.0");
            }
            rule.setMultiplier(body.multiplier());
        }
        if (body.startTime() != null) {
            rule.setStartTime(body.startTime());
        }
        if (body.endTime() != null) {
            rule.setEndTime(body.endTime());
        }
        if (body.active() != null && !body.active()) {
            pricingService.deactivate(ruleId);
        }
        return pricingService.getRule(ruleId).orElse(rule);
    }

    @DeleteMapping("/api/v1/pricing/rules/{ruleId}")
    @Transactional
    public Map<String, Object> deletePricingRule(@AuthenticationPrincipal TokenPrincipal principal,
                                                 @PathVariable Long ruleId) {
        var rule = pricingService.getRule(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Pricing rule not found"));
        ownerGuard.requireOwnerOrAdmin(principal, rule.getRestaurantId());
        pricingService.deactivate(ruleId);
        return Map.of("message", "Pricing rule deactivated", "id", ruleId);
    }

    /** Happy-hour view: rules whose window covers "now" (dev: all active). */
    @GetMapping("/api/v1/pricing/restaurants/{restaurantId}/happy-hour")
    @Transactional(readOnly = true)
    public Map<String, Object> happyHour(@AuthenticationPrincipal TokenPrincipal principal,
                                         @PathVariable Long restaurantId) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        return Map.of(
                "restaurantId", restaurantId,
                "activeRules", pricingService.active(restaurantId),
                "happyHourActive", false);
    }
}
