package com.bhukkad.restaurant.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.restaurant.domain.entity.MenuItem;
import com.bhukkad.restaurant.domain.repository.MenuItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bulk menu upsert (port of monolith {@code MenuBulkController}).
 *
 * <p>Owner/admin gated, item lookups are scoped to the path restaurant
 * (no cross-tenant re-parenting), and the whole batch commits atomically.</p>
 */
@RestController
@RequestMapping("/api/v1/restaurants/{restaurantId}/menu/bulk")
@RequiredArgsConstructor
public class MenuBulkController {

    private static final int MAX_ITEMS = 200;

    private final MenuItemRepository menuItemRepository;
    private final RestaurantOwnerController ownerGuard;
    private final com.bhukkad.restaurant.infrastructure.cache.MenuCacheInvalidator cacheInvalidator;
    private final com.bhukkad.restaurant.domain.event.MenuEventsPublisher menuEventsPublisher;

    public record BulkItem(Long id, String name, BigDecimal price, Boolean available) {}

    @PostMapping
    @Transactional
    public List<MenuItem> bulkUpsert(@AuthenticationPrincipal TokenPrincipal principal,
                                     @PathVariable Long restaurantId,
                                     @RequestBody List<BulkItem> items) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        if (items == null || items.isEmpty()) {
            throw new BusinessException("Bulk list must not be empty");
        }
        if (items.size() > MAX_ITEMS) {
            throw new BusinessException("Bulk list exceeds " + MAX_ITEMS + " items");
        }
        List<MenuItem> saved = items.stream().map(item -> {
            if (item.name() == null || item.name().isBlank()) {
                throw new BusinessException("Item name is required");
            }
            if (item.price() == null || item.price().signum() < 0) {
                throw new BusinessException("Item price must be >= 0");
            }
            // Scoped lookup: a caller-supplied id belonging to ANOTHER
            // restaurant must never be silently re-parented here.
            MenuItem mi = item.id() != null
                    ? menuItemRepository.findByIdAndRestaurantId(item.id(), restaurantId)
                            .orElseThrow(() -> new BusinessException(
                                    "Item " + item.id() + " does not belong to restaurant " + restaurantId))
                    : new MenuItem();
            mi.setRestaurantId(restaurantId);
            mi.setName(item.name());
            mi.setPrice(item.price());
            mi.setIsAvailable(item.available() == null || item.available());
            return menuItemRepository.save(mi);
        }).toList();
        // PERF-3: the whole batch mutates this restaurant's menu caches.
        cacheInvalidator.invalidateMenu(restaurantId);
        for (MenuItem mi : saved) {
            cacheInvalidator.invalidateMenuItem(mi.getId());
            // ADR-002 search sync: one menu_item_changed per upserted item,
            // committed in this same transaction.
            menuEventsPublisher.menuItemChanged(mi, null);
        }
        return saved;
    }
}
