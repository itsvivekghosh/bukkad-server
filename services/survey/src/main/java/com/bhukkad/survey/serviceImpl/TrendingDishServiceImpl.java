package com.bhukkad.survey.serviceImpl;

import com.bhukkad.common.cache.LocalCacheService;
import com.bhukkad.survey.TrendingDishService;
import com.bhukkad.survey.dto.response.TrendingDishResponse;
import com.bhukkad.survey.entity.TrendingDish;
import com.bhukkad.survey.repository.TrendingDishRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Trending dishes for the home feed.
 *
 * <p>Ranks menu items by quantity sold from the ANALYTICS-owned
 * {@code trending_dishes} summary table (fed by the ORDER_ITEMS_SNAPSHOT outbox
 * event). Results are cached in-process for 60 seconds so the anonymous home
 * feed never hits the database more than once per minute. Failures degrade to
 * an empty list.
 */
@Service
public class TrendingDishServiceImpl implements TrendingDishService {

    private static final Logger log = LoggerFactory.getLogger(TrendingDishServiceImpl.class);

    private static final String CACHE_KEY = "trending-dishes";
    private static final long CACHE_TTL_SECONDS = 60;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final TrendingDishRepository trendingDishRepository;
    private final LocalCacheService localCacheService;

    public TrendingDishServiceImpl(TrendingDishRepository trendingDishRepository,
                                   LocalCacheService localCacheService) {
        this.trendingDishRepository = trendingDishRepository;
        this.localCacheService = localCacheService;
    }

    /**
     * Returns the top {@code limit} trending dishes, cached for 60 seconds.
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<TrendingDishResponse> trending(int limit) {
        int requested = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        try {
            List<TrendingDishResponse> cached = (List<TrendingDishResponse>) localCacheService.getOrCompute(
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

    @Transactional(readOnly = true)
    List<TrendingDishResponse> queryTrending() {
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