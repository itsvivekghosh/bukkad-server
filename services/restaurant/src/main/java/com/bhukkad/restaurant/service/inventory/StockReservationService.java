package com.bhukkad.restaurant.service.inventory;

import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import com.bhukkad.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

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

    public void reserveStock(List<StockReservationItem> items) {
        if (!isEnabled()) {
            return;
        }
        for (StockReservationItem item : items) {
            MenuItem menuItem = menuItemRepository.findById(item.menuItemId()).orElse(null);
            if (menuItem == null || menuItem.getStockQuantity() == null) {
                continue;
            }
            String key = STOCK_KEY_PREFIX + menuItem.getId();
            try {
                stringRedisTemplate.opsForValue().setIfAbsent(
                        key,
                        String.valueOf(menuItem.getStockQuantity()),
                        Duration.ofSeconds(properties.getReservationTtlSeconds()));

                Long remaining = stringRedisTemplate.opsForValue().decrement(key, item.quantity());
                if (remaining == null || remaining < 0) {
                    stringRedisTemplate.opsForValue().increment(key, item.quantity());
                    throw new BusinessException("Insufficient stock for: " + item.menuItemName());
                }
            } catch (DataAccessException ex) {
                log.warn("Stock reservation unavailable, falling back to DB check | itemId={} | error={}",
                        menuItem.getId(), ex.getMessage());
            }
        }
    }

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

    public void releaseStock(List<StockReservationItem> items) {
        if (!isEnabled()) {
            return;
        }
        for (StockReservationItem item : items) {
            try {
                stringRedisTemplate.opsForValue().increment(
                        STOCK_KEY_PREFIX + item.menuItemId(),
                        item.quantity());
            } catch (DataAccessException ex) {
                log.warn("Stock release unavailable | itemId={} | error={}", item.menuItemId(), ex.getMessage());
            }
        }
    }
}
