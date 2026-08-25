package com.bhukkad.inventory;

import com.bhukkad.config.StockReservationProperties;
import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.exception.BusinessException;
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

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public void reserveStock(List<CartItem> cartItems) {
        if (!isEnabled()) {
            return;
        }
        for (CartItem cartItem : cartItems) {
            MenuItem menuItem = cartItem.getMenuItem();
            if (menuItem.getStockQuantity() == null) {
                continue;
            }
            String key = STOCK_KEY_PREFIX + menuItem.getId();
            try {
                stringRedisTemplate.opsForValue().setIfAbsent(
                        key,
                        String.valueOf(menuItem.getStockQuantity()),
                        Duration.ofSeconds(properties.getReservationTtlSeconds()));

                Long remaining = stringRedisTemplate.opsForValue().decrement(key, cartItem.getQuantity());
                if (remaining == null || remaining < 0) {
                    stringRedisTemplate.opsForValue().increment(key, cartItem.getQuantity());
                    throw new BusinessException("Insufficient stock for: " + menuItem.getName());
                }
            } catch (DataAccessException ex) {
                // Redis unavailable: fail open. The DB atomic decrement
                // (MenuItemRepository#decrementStockAtomic) is the authoritative
                // oversell guard, so a Redis outage must not block order placement.
                log.warn("Stock reservation unavailable, falling back to DB check | itemId={} | error={}",
                        menuItem.getId(), ex.getMessage());
            }
        }
    }

    public void syncStock(MenuItem menuItem) {
        if (!isEnabled() || menuItem == null || menuItem.getId() == null || menuItem.getStockQuantity() == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    STOCK_KEY_PREFIX + menuItem.getId(),
                    String.valueOf(menuItem.getStockQuantity()),
                    Duration.ofSeconds(properties.getReservationTtlSeconds()));
        } catch (DataAccessException ex) {
            log.warn("Stock sync unavailable | itemId={} | error={}", menuItem.getId(), ex.getMessage());
        }
    }

    public void releaseStock(List<CartItem> cartItems) {
        if (!isEnabled()) {
            return;
        }
        for (CartItem cartItem : cartItems) {
            MenuItem menuItem = cartItem.getMenuItem();
            if (menuItem.getStockQuantity() == null) {
                continue;
            }
            try {
                stringRedisTemplate.opsForValue().increment(
                        STOCK_KEY_PREFIX + menuItem.getId(),
                        cartItem.getQuantity());
            } catch (DataAccessException ex) {
                log.warn("Stock release unavailable | itemId={} | error={}", menuItem.getId(), ex.getMessage());
            }
        }
    }
}
