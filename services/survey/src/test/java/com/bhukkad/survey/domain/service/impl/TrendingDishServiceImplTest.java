package com.bhukkad.survey.domain.service.impl;

import com.bhukkad.common.cache.LocalCacheService;
import com.bhukkad.survey.api.dto.response.TrendingDishResponse;
import com.bhukkad.survey.domain.entity.TrendingDish;
import com.bhukkad.survey.domain.repository.TrendingDishRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Home-feed trending ranks: 60 s cache, limit clamping (default 10, hard cap
 * 50) and fail-open-to-empty degradation when the cache/loader throws.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class TrendingDishServiceImplTest {
    @Mock private TrendingDishRepository trendingDishRepository;
    @Mock private LocalCacheService localCacheService;
    @InjectMocks private TrendingDishServiceImpl service;

    private List<TrendingDishResponse> dishes(int n) {
        List<TrendingDishResponse> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(new TrendingDishResponse((long) i, "dish-" + i, (long) (100 - i)));
        }
        return list;
    }

    private void cacheReturns(List<TrendingDishResponse> value) {
        when(localCacheService.getOrCompute(eq("trending-dishes"), eq(List.class), eq(60L), any()))
                .thenReturn(value);
    }

    @Test
    void nonPositiveLimit_fallsBackToDefaultTen() {
        cacheReturns(dishes(3));

        assertThat(service.trending(0)).hasSize(3);

        verify(localCacheService).getOrCompute(eq("trending-dishes"), eq(List.class), eq(60L), any());
    }

    @Test
    void cachedSmallerThanRequested_returnedWhole() {
        cacheReturns(dishes(5));

        assertThat(service.trending(25)).hasSize(5);
    }

    @Test
    void cachedLargerThanRequested_truncatedToLimit() {
        cacheReturns(dishes(60));

        List<TrendingDishResponse> top50 = service.trending(50);

        assertThat(top50).hasSize(50);
        assertThat(top50.get(0).id()).isEqualTo(1L);
        assertThat(top50.get(49).id()).isEqualTo(50L);
    }

    @Test
    void oversizedLimit_clampedToMax50() {
        cacheReturns(dishes(60));

        // 60 cached, request 999 → capped to 50 → truncated to ids 1..50
        assertThat(service.trending(999)).hasSize(50);
    }

    @Test
    void cacheFailure_degradesToEmptyList() {
        when(localCacheService.getOrCompute(any(), any(), eq(60L), any()))
                .thenThrow(new RuntimeException("cache exploded"));

        assertThat(service.trending(10)).isEmpty();
    }

    @Test
    void queryTrending_mapsEntitiesToDtos() throws Exception {
        TrendingDish first = new TrendingDish();
        first.setMenuItemId(11L);
        first.setDishName("Butter Chicken");
        first.setQuantitySold(120L);
        TrendingDish second = new TrendingDish();
        second.setMenuItemId(22L);
        second.setDishName("Biryani");
        second.setQuantitySold(80L);
        when(trendingDishRepository.findTopByQuantitySoldDesc(PageRequest.of(0, 50)))
                .thenReturn(List.of(first, second));

        ArgumentCaptor<Supplier> loader = ArgumentCaptor.forClass(Supplier.class);
        cacheReturns(List.of());
        service.trending(10);
        verify(localCacheService).getOrCompute(eq("trending-dishes"), eq(List.class), eq(60L), loader.capture());

        List<TrendingDishResponse> loaded = (List<TrendingDishResponse>) loader.getValue().get();
        assertThat(loaded).containsExactly(
                new TrendingDishResponse(11L, "Butter Chicken", 120L),
                new TrendingDishResponse(22L, "Biryani", 80L));
    }

    @Test
    void queryTrending_directPageableProbe() {
        when(trendingDishRepository.findTopByQuantitySoldDesc(any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.queryTrending()).isEmpty();
    }
}
