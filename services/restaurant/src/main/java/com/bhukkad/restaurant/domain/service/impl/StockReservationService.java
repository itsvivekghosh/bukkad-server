package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.restaurant.domain.entity.MenuItem;
import com.bhukkad.restaurant.domain.repository.MenuItemRepository;
import com.bhukkad.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import com.bhukkad.restaurant.api.dto.request.StockReservationItem;
import com.bhukkad.restaurant.config.StockReservationProperties;

/**
 * Stock reservation for checkout (audit batch C): the DATABASE row is the
 * single source of truth via guarded atomic updates
 * ({@code MenuItemRepository.decrementStockAtomic}, {@code >= quantity} in
 * the WHERE — no oversell under any interleaving).
 *
 * <p>Semantics fixed from the pre-audit implementation:</p>
 * <ul>
 *   <li><b>Fail-closed:</b> an infrastructure error (DB or Redis) aborts the
 *       reservation as a business failure. The old code logged a WARN and let
 *       the order proceed — every Redis outage silently re-opened overselling.</li>
 *   <li><b>Multi-item all-or-nothing:</b> if any line cannot be reserved, the
 *       already-reserved lines are restored before the exception propagates.</li>
 *   <li><b>Redis is a pre-check mirror, never the gate:</b> after the DB row
 *       is held, the shared counter is seeded/decremented. A failure there is
 *       logged and triggers a self-healing sync of that key — the reservation
 *       stands on the database alone.</li>
 *   <li>Untracked items ({@code stock_quantity IS NULL}) are always allowed
 *       (no row state to decrement), parity with legacy behaviour.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReservationService {

    private static final String STOCK_KEY_PREFIX = "stock:item:";

    private final StringRedisTemplate stringRedisTemplate;
    private final StockReservationProperties properties;
    private final MenuItemRepository menuItemRepository;

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Reserves every line of a checkout atomically against the DB rows.
     * Throws {@link BusinessException} on insufficient stock (4xx-mapped) or
     * infrastructure failure (fail-closed); partial reservations are always
     * compensated back before throwing.
     */
    @Transactional
    public void reserveStock(List<StockReservationItem> items) {
        if (!isEnabled()) {
            return;
        }
        List<StockReservationItem> held = new ArrayList<>();
        try {
            for (StockReservationItem item : items) {
                reserveOne(item, held);
            }
        } catch (RuntimeException failure) {
            compensate(held);
            throw failure;
        }
    }

    private void reserveOne(StockReservationItem item, List<StockReservationItem> held) {
        MenuItem menuItem = menuItemRepository.findById(item.menuItemId()).orElse(null);
        if (menuItem == null || menuItem.getStockQuantity() == null) {
            return; // untracked -> no DB gate to hold
        }
        int updated;
        try {
            updated = menuItemRepository.decrementStockAtomic(menuItem.getId(), item.quantity());
        } catch (DataAccessException ex) {
            // Fail CLOSED: an unreadable inventory row can never mean "allowed".
            log.error("STOCK_DB_UNAVAILABLE | itemId={} | error={}", menuItem.getId(), ex.getMessage());
            throw new BusinessException("Inventory temporarily unavailable for: "
                    + item.menuItemName());
        }
        if (updated == 0) {
            throw new BusinessException("Insufficient stock for: " + item.menuItemName());
        }
        held.add(item);
        mirrorDecrement(menuItem.getId());
    }

    private void mirrorDecrement(Long menuItemId) {
        String key = STOCK_KEY_PREFIX + menuItemId;
        try {
            stringRedisTemplate.opsForValue().decrement(key);
        } catch (DataAccessException ex) {
            log.warn("STOCK_MIRROR_DECREMENT_FAILED | itemId={} | error={}", menuItemId, ex.getMessage());
        }
    }

    /** Restores a compensation batch back to the DB rows (best effort, log loud). */
    private void compensate(List<StockReservationItem> held) {
        for (StockReservationItem item : held) {
            try {
                menuItemRepository.restoreStockAtomic(item.menuItemId(), item.quantity());
                syncStock(item.menuItemId());
            } catch (RuntimeException ex) {
                log.error("STOCK_COMPENSATION_FAILED | itemId={} | qty={} | error={}",
                        item.menuItemId(), item.quantity(), ex.getMessage());
            }
        }
    }

    /**
     * Refreshes the shared pre-check counter from the authoritative row.
     */
    public void syncStock(Long menuItemId) {
        if (!isEnabled() || menuItemId == null) {
            return;
        }
        MenuItem menuItem = menuItemRepository.findById(menuItemId).orElse(null);
        if (menuItem == null || menuItem.getStockQuantity() == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    STOCK_KEY_PREFIX + menuItemId,
                    String.valueOf(menuItem.getStockQuantity()),
                    Duration.ofSeconds(properties.getReservationTtlSeconds()));
        } catch (DataAccessException ex) {
            log.warn("Stock sync unavailable | itemId={} | error={}", menuItemId, ex.getMessage());
        }
    }

    /**
     * Cancellation compensation path: restores DB rows first (authoritative),
     * then re-syncs the mirror. A DB restore failure is logged loudly — the
     * row is the system of record and the drift must be visible to ops.
     */
    @Transactional
    public void releaseStock(List<StockReservationItem> items) {
        if (!isEnabled()) {
            return;
        }
        for (StockReservationItem item : items) {
            MenuItem menuItem = menuItemRepository.findById(item.menuItemId()).orElse(null);
            if (menuItem == null || menuItem.getStockQuantity() == null) {
                continue;
            }
            try {
                menuItemRepository.restoreStockAtomic(item.menuItemId(), item.quantity());
            } catch (DataAccessException ex) {
                log.error("STOCK_DB_RELEASE_FAILED | itemId={} | qty={} | error={}",
                        item.menuItemId(), item.quantity(), ex.getMessage());
            }
            syncStock(item.menuItemId());
        }
    }
}
