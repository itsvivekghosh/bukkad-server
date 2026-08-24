package com.bhukkad.survey;

import com.bhukkad.cache.LocalCacheService;
import com.bhukkad.dto.response.TrendingDishResponse;
import com.bhukkad.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Real-time trending dishes for the home feed.
 *
 * <p>Ranks menu items by order-item count over the last 60 minutes and caches
 * the result in-process for 60 seconds (via {@link LocalCacheService}) so the
 * anonymous home feed never hits the database more than once per minute. Query
 * failures degrade to an empty list rather than an error response.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendingDishService {

    private static final String CACHE_KEY = "trending-dishes";
    private static final long CACHE_TTL_SECONDS = 60;
    private static final int WINDOW_MINUTES = 60;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final OrderItemRepository orderItemRepository;
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
        List<Object[]> rows = orderItemRepository.findTrendingByCreatedSince(
                LocalDateTime.now().minusMinutes(WINDOW_MINUTES));
        return rows.stream()
                .map(row -> new TrendingDishResponse(
                        (Long) row[0],
                        (String) row[1],
                        ((Number) row[2]).longValue()))
                .limit(MAX_LIMIT)
                .collect(Collectors.toList());
    }
}
