package com.bhukkad.survey;

import com.bhukkad.common.cache.LocalCacheService;
import com.bhukkad.common.datasource.UseReadReplica;
import com.bhukkad.dto.response.TrendingDishResponse;
import com.bhukkad.entity.TrendingDish;
import com.bhukkad.repository.TrendingDishRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Trending dishes for the home feed.
 *
 * <p>Ranks menu items by quantity sold from the ANALYTICS-owned
 * {@code trending_dishes} summary table (fed by the ORDER_ITEMS_SNAPSHOT outbox
 * event — see {@link TrendingDishMaterializer}). The home feed no longer joins
 * order_items with menu_items across domain boundaries. Results are cached
 * in-process for 60 seconds so the anonymous home feed never hits the database
 * more than once per minute. Failures degrade to an empty list.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@UseReadReplica
public class TrendingDishService {

    private static final String CACHE_KEY = "trending-dishes";
    private static final long CACHE_TTL_SECONDS = 60;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final TrendingDishRepository trendingDishRepository;
    private final LocalCacheService localCacheService;

    /**
     * Returns the top {@code limit} trending dishes, cached for 60 seconds.
     *
     * @param limit desired result size; non-positive values fall back to
     *              {@value #DEFAULT_LIMIT} and values above {@value #MAX_LIMIT}
     *              are capped
     * @return ranked dishes, never {@code null}; empty when the query fails
     */
    @SuppressWarnings("unchecked")
    public List<TrendingDishResponse> trending(int limit) {
        int requested = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        try {
            List<TrendingDishResponse> cached = localCacheService.getOrCompute(
                    CACHE_KEY, List.class, CACHE_TTL_SECONDS, this::queryTrending);
            if (cached.size() <= requested) {
                return cached;
            }
            return new ArrayList<>(cached.subList(0, requested));
        } catch (Exception ex) {
            log.warn("Failed to compute trending dishes | key={} | error={}",
                    CACHE_KEY, ex.getMessage());
            return List.of();
        }
    }

    private List<TrendingDishResponse> queryTrending() {
        List<TrendingDish> dishes = trendingDishRepository
                .findTopByQuantitySoldDesc(PageRequest.of(0, MAX_LIMIT));
        return dishes.stream()
                .map(d -> new TrendingDishResponse(
                        d.getMenuItemId(),
                        d.getDishName(),
                        d.getQuantitySold()))
                .toList();
    }
}
