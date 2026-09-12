package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.restaurant.domain.entity.MenuItem;
import com.bhukkad.restaurant.domain.repository.MenuItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.bhukkad.restaurant.config.StockReservationProperties;
import com.bhukkad.restaurant.api.dto.request.StockReservationItem;

/**
 * Audit batch C reservation semantics: the database row is the gate
 * (guarded atomic update), infrastructure errors fail CLOSED, and a failed
 * multi-item checkout compensates everything it already held.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockReservationServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private MenuItemRepository menuItemRepository;
    private StockReservationProperties properties;
    private StockReservationService service;

    @BeforeEach
    void setUp() {
        properties = new StockReservationProperties();
        properties.setEnabled(true);
        service = new StockReservationService(redis, properties, menuItemRepository);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    private MenuItem tracked(long id, int stock) {
        MenuItem m = new MenuItem();
        m.setId(id);
        m.setName("Butter Chicken " + id);
        m.setStockQuantity(stock);
        return m;
    }

    @Test
    void reservesAgainstDbRowAndMirrorsToRedis() {
        when(menuItemRepository.findById(1L)).thenReturn(java.util.Optional.of(tracked(1L, 10)));
        when(menuItemRepository.decrementStockAtomic(1L, 3)).thenReturn(1);

        service.reserveStock(List.of(new StockReservationItem(1L, "Butter Chicken 1", 3)));

        verify(menuItemRepository).decrementStockAtomic(1L, 3);
        verify(valueOps).decrement("stock:item:1");
    }

    @Test
    void insufficientStock_throwsBusinessError() {
        when(menuItemRepository.findById(1L)).thenReturn(java.util.Optional.of(tracked(1L, 2)));
        when(menuItemRepository.decrementStockAtomic(1L, 3)).thenReturn(0);

        assertThatThrownBy(() -> service.reserveStock(
                List.of(new StockReservationItem(1L, "Butter Chicken 1", 3))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Insufficient stock");
    }

    @Test
    void multiItemFailure_compensatesAlreadyHeldLines() {
        when(menuItemRepository.findById(1L)).thenReturn(java.util.Optional.of(tracked(1L, 10)));
        when(menuItemRepository.decrementStockAtomic(1L, 2)).thenReturn(1);
        when(menuItemRepository.findById(2L)).thenReturn(java.util.Optional.of(tracked(2L, 1)));
        when(menuItemRepository.decrementStockAtomic(2L, 5)).thenReturn(0);

        assertThatThrownBy(() -> service.reserveStock(List.of(
                new StockReservationItem(1L, "A", 2),
                new StockReservationItem(2L, "B", 5))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("B"); // the second line carries the failure

        // first held line restored exactly once
        verify(menuItemRepository).restoreStockAtomic(1L, 2);
    }

    @Test
    void dbOutage_failsClosedWithoutOverselling() {
        when(menuItemRepository.findById(1L)).thenReturn(java.util.Optional.of(tracked(1L, 10)));
        when(menuItemRepository.decrementStockAtomic(1L, 1))
                .thenThrow(new DataAccessResourceFailureException("db down",
                        new java.io.IOException("db down")));

        assertThatThrownBy(() -> service.reserveStock(
                List.of(new StockReservationItem(1L, "A", 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void redisOutage_doesNotBlockDbHeldReservation() {
        when(menuItemRepository.findById(1L)).thenReturn(java.util.Optional.of(tracked(1L, 10)));
        when(menuItemRepository.decrementStockAtomic(1L, 1)).thenReturn(1);
        when(valueOps.decrement(anyString()))
                .thenThrow(new RedisConnectionFailureException("Connection refused",
                        new java.io.IOException("boom")));

        service.reserveStock(List.of(new StockReservationItem(1L, "A", 1))); // must not throw

        verify(menuItemRepository, never()).restoreStockAtomic(anyLong(), anyInt());
    }

    @Test
    void untrackedItem_needsNoDbRow() {
        MenuItem untracked = new MenuItem();
        untracked.setId(1L);
        untracked.setName("Vada Pav 1");
        untracked.setStockQuantity(null);
        when(menuItemRepository.findById(1L)).thenReturn(java.util.Optional.of(untracked));

        service.reserveStock(List.of(new StockReservationItem(1L, "Vada Pav 1", 99)));

        verify(menuItemRepository, never()).decrementStockAtomic(anyLong(), anyInt());
    }

    @Test
    void release_restoresRowThenResyncs() {
        when(menuItemRepository.findById(1L)).thenReturn(java.util.Optional.of(tracked(1L, 10)));
        when(menuItemRepository.restoreStockAtomic(1L, 3)).thenReturn(1);

        service.releaseStock(List.of(new StockReservationItem(1L, "A", 3)));

        verify(menuItemRepository).restoreStockAtomic(1L, 3);
        verify(valueOps).set(eq("stock:item:1"), anyString(), any(java.time.Duration.class));
    }

    @Test
    void disabled_feature_shortCircuits() {
        properties.setEnabled(false);
        service.reserveStock(List.of(new StockReservationItem(1L, "A", 50)));
        verify(menuItemRepository, never()).findById(anyLong());
    }
}
