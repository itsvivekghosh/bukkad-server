package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import com.bhukkad.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bulk menu upsert (port of monolith {@code MenuBulkController}).
 */
@RestController
@RequestMapping("/api/v1/restaurants/{restaurantId}/menu/bulk")
@RequiredArgsConstructor
public class MenuBulkController {

    private final MenuItemRepository menuItemRepository;

    public record BulkItem(Long id, String name, BigDecimal price, Boolean available) {}

    @PostMapping
    public List<MenuItem> bulkUpsert(@PathVariable Long restaurantId, @RequestBody List<BulkItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException("Bulk list must not be empty");
        }
        return items.stream().map(item -> {
            MenuItem mi = item.id() != null
                    ? menuItemRepository.findById(item.id()).orElseGet(MenuItem::new)
                    : new MenuItem();
            mi.setRestaurantId(restaurantId);
            mi.setName(item.name());
            mi.setPrice(item.price());
            mi.setIsAvailable(item.available() == null || item.available());
            return menuItemRepository.save(mi);
        }).toList();
    }
}