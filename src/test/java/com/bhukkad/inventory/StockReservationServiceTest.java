package com.bhukkad.inventory;

import com.bhukkad.config.StockReservationProperties;
import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockReservationServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private StockReservationProperties properties;

    @InjectMocks
    private StockReservationService service;

    private CartItem cartItem;
    private MenuItem menuItem;

    @BeforeEach
    void setUp() {
        menuItem = new MenuItem();
        menuItem.setId(5L);
        menuItem.setName("Paneer");
        menuItem.setStockQuantity(10);

        cartItem = new CartItem();
        cartItem.setMenuItem(menuItem);
        cartItem.setQuantity(2);

        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void reserveStock_skips_whenDisabled() {
        when(properties.isEnabled()).thenReturn(false);
        service.reserveStock(List.of(cartItem));
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void reserveStock_reservesAndDecrements() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getReservationTtlSeconds()).thenReturn(900L);
        when(valueOps.setIfAbsent(eq("stock:item:5"), eq("10"), any(Duration.class))).thenReturn(true);
        when(valueOps.decrement("stock:item:5", 2L)).thenReturn(8L);

        service.reserveStock(List.of(cartItem));
        verify(valueOps).decrement("stock:item:5", 2L);
    }

    @Test
    void reserveStock_throwsAndRestores_whenInsufficientStock() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getReservationTtlSeconds()).thenReturn(900L);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(valueOps.decrement("stock:item:5", 2L)).thenReturn(-1L);

        assertThrows(BusinessException.class, () -> service.reserveStock(List.of(cartItem)));
        verify(valueOps).increment("stock:item:5", 2L);
    }

    @Test
    void reserveStock_skipsItemsWithoutStock() {
        menuItem.setStockQuantity(null);
        when(properties.isEnabled()).thenReturn(true);

        service.reserveStock(List.of(cartItem));
        verify(valueOps, never()).decrement(anyString(), any(Long.class));
    }

    @Test
    void syncStock_setsCurrentStock() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getReservationTtlSeconds()).thenReturn(900L);

        service.syncStock(menuItem);
        verify(valueOps).set(eq("stock:item:5"), eq("10"), any(Duration.class));
    }

    @Test
    void syncStock_skipsNullOrMissingStock() {
        when(properties.isEnabled()).thenReturn(true);
        service.syncStock(null);
        menuItem.setStockQuantity(null);
        service.syncStock(menuItem);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void releaseStock_increments() {
        when(properties.isEnabled()).thenReturn(true);
        service.releaseStock(List.of(cartItem));
        verify(valueOps).increment("stock:item:5", 2L);
    }

    @Test
    void isEnabled_delegatesToProperties() {
        when(properties.isEnabled()).thenReturn(true);
        service.isEnabled();
        verify(properties).isEnabled();
    }
}