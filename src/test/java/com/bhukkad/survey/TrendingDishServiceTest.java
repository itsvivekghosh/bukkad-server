package com.bhukkad.survey;

import com.bhukkad.common.cache.LocalCacheService;
import com.bhukkad.dto.response.TrendingDishResponse;
import com.bhukkad.entity.TrendingDish;
import com.bhukkad.repository.TrendingDishRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrendingDishServiceTest {

    @Mock
    private TrendingDishRepository trendingDishRepository;

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

    private TrendingDish dish(long id, String name, long qty) {
        return TrendingDish.builder()
                .menuItemId(id)
                .restaurantId(1L)
                .dishName(name)
                .quantitySold(qty)
                .build();
    }

    @Test
    void trending_mapsMaterializedRowsInOrder() {
        when(trendingDishRepository.findTopByQuantitySoldDesc(any(Pageable.class)))
                .thenReturn(List.of(dish(1L, "Butter Chicken", 3L), dish(2L, "Paneer Tikka", 1L)));
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
        when(trendingDishRepository.findTopByQuantitySoldDesc(any(Pageable.class)))
                .thenReturn(List.of(dish(1L, "Butter Chicken", 3L), dish(2L, "Paneer Tikka", 1L)));
        stubCacheToInvokeSupplier();

        List<TrendingDishResponse> result = service.trending(1);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
    }

    @Test
    void trending_defaultsLimitWhenNonPositive() {
        when(trendingDishRepository.findTopByQuantitySoldDesc(any(Pageable.class)))
                .thenReturn(List.of(dish(1L, "Butter Chicken", 3L)));
        stubCacheToInvokeSupplier();

        List<TrendingDishResponse> result = service.trending(0);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
    }

    @Test
    void trending_returnsEmptyWhenQueryFails() {
        when(trendingDishRepository.findTopByQuantitySoldDesc(any(Pageable.class)))
                .thenThrow(new RuntimeException("db down"));
        stubCacheToInvokeSupplier();

        List<TrendingDishResponse> result = service.trending(10);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
