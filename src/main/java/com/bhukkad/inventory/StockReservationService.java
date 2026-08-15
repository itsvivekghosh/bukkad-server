package com.bhukkad.inventory;

import com.bhukkad.config.StockReservationProperties;
import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

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
            stringRedisTemplate.opsForValue().setIfAbsent(
                    key,
                    String.valueOf(menuItem.getStockQuantity()),
                    Duration.ofSeconds(properties.getReservationTtlSeconds()));

            Long remaining = stringRedisTemplate.opsForValue().decrement(key, cartItem.getQuantity());
            if (remaining == null || remaining < 0) {
                stringRedisTemplate.opsForValue().increment(key, cartItem.getQuantity());
                throw new BusinessException("Insufficient stock for: " + menuItem.getName());
            }
        }
    }

    public void syncStock(MenuItem menuItem) {
        if (!isEnabled() || menuItem == null || menuItem.getId() == null || menuItem.getStockQuantity() == null) {
            return;
        }
        stringRedisTemplate.opsForValue().set(
                STOCK_KEY_PREFIX + menuItem.getId(),
                String.valueOf(menuItem.getStockQuantity()),
                Duration.ofSeconds(properties.getReservationTtlSeconds()));
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
            stringRedisTemplate.opsForValue().increment(
                    STOCK_KEY_PREFIX + menuItem.getId(),
                    cartItem.getQuantity());
        }
    }
}
