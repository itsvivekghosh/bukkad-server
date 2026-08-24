package com.bhukkad.survey;

import com.bhukkad.cache.LocalCacheService;
import com.bhukkad.dto.response.TrendingDishResponse;
import com.bhukkad.repository.OrderItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrendingDishServiceTest {

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private LocalCacheService localCacheService;

    @InjectMocks
    private TrendingDishService service;

    private void stubCacheToInvokeSupplier() {
        when(localCacheService.getOrCompute(anyString(), eq(List.class), eq(60L), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Supplier<List<TrendingDishResponse>> supplier = inv.getArgument(3);
                    return supplier.get();
                });
    }

    @Test
    void trending_mapsQueryRowsInOrder() {
        when(orderItemRepository.findTrendingByCreatedSince(any()))
                .thenReturn(List.of(
                        new Object[]{1L, "Butter Chicken", 3L},
                        new Object[]{2L, "Paneer Tikka", 1L}));
        stubCacheToInvokeSupplier();

        List<TrendingDishResponse> result = service.trending(2);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals("Butter Chicken", result.get(0).name());
        assertEquals(3L, result.get(0).orderItemCount());
        assertEquals(2L, result.get(1).id());
        assertEquals("Paneer Tikka", result.get(1).name());
        assertEquals(1L, result.get(1).orderItemCount());
    }

    @Test
    void trending_appliesRequestedLimit() {
        when(orderItemRepository.findTrendingByCreatedSince(any()))
                .thenReturn(List.of(
                        new Object[]{1L, "Butter Chicken", 3L},
                        new Object[]{2L, "Paneer Tikka", 1L}));
        stubCacheToInvokeSupplier();

        List<TrendingDishResponse> result = service.trending(1);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
    }

    @Test
    void trending_defaultsLimitWhenNonPositive() {
        when(orderItemRepository.findTrendingByCreatedSince(any()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, "Butter Chicken", 3L}));
        stubCacheToInvokeSupplier();

        List<TrendingDishResponse> result = service.trending(0);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
    }

    @Test
    void trending_returnsEmptyWhenQueryFails() {
        when(orderItemRepository.findTrendingByCreatedSince(any()))
                .thenThrow(new RuntimeException("db down"));
        stubCacheToInvokeSupplier();

        List<TrendingDishResponse> result = service.trending(10);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
