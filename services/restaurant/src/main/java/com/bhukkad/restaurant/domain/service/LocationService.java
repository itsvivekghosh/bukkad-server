package com.bhukkad.restaurant.domain.service;

import com.bhukkad.common.cache.FeedCacheKeys;
import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.common.util.GeohashUtils;
import com.bhukkad.restaurant.api.dto.response.RestaurantSummary;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Phase 1 spatial foundation: ultra-low-latency nearby-restaurant discovery.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>L1 in-JVM cache (short TTL, bounded size).</li>
 *   <li>L2 Redis cache via {@link RedisCacheService}.</li>
 *   <li>PostGIS query via {@link RestaurantRepository#findNearbyWithDistance}.</li>
 * </ol>
 * Cold misses are cached in Redis for subsequent requests.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocationService {

    private static final int GEOHASH_PRECISION = 4; // ~20 km cells
    private static final int DEFAULT_LIMIT = 100;

    private final RestaurantRepository restaurantRepository;
    private final ObjectProvider<RedisCacheService> cacheProvider;
    private final RedisTemplate<String, String> redisTemplate;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate newTransactionTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template;
    }

    /**
     * Find nearby restaurants with multi-tier caching.
     *
     * <p>Target: &lt; 50 ms p95 at 20K TPS once Redis is warm.</p>
     */
    public List<RestaurantSummary> findNearby(double lat, double lng, double radiusKm) {
        String geohash = GeohashUtils.encode(lat, lng, GEOHASH_PRECISION);
        String radiusBucket = FeedCacheKeys.radiusBucket(radiusKm);
        String cacheKey = FeedCacheKeys.nearbyKey(geohash, radiusBucket, "-");

        // 1. L1/L2 cache (RedisCacheService wraps LocalCacheService L1 + Redis L2).
        RedisCacheService cache = cacheProvider.getIfAvailable();
        if (cache != null) {
            List<Object[]> cached = cache.getListOrCompute(
                    cacheKey,
                    Object[].class,
                    FeedCacheKeys.FEED_NEARBY_L2_TTL_SECONDS,
                    () -> loadFromPostGIS(lat, lng, radiusKm)
            );
            if (cached != null && !cached.isEmpty()) {
                return cached.stream().map(this::toSummary).toList();
            }
        }

        // 2. No Redis available or cache returned null — direct DB fallback.
        List<Object[]> rows = loadFromPostGIS(lat, lng, radiusKm);
        return rows.stream().map(this::toSummary).toList();
    }

    /**
     * Load from PostGIS via the optimized native query.
     * Returns empty list on any failure so the caller can fall back.
     */
    private List<Object[]> loadFromPostGIS(double lat, double lng, double radiusKm) {
        try {
            TransactionTemplate template = newTransactionTemplate();
            return template.execute(status -> {
                try {
                    double radiusMeters = radiusKm * 1000.0;
                    return restaurantRepository.findNearbyWithDistance(
                            lat, lng, radiusMeters, DEFAULT_LIMIT);
                } catch (Exception ex) {
                    log.debug("POSTGIS_NEARBY_FAILED lat={} lng={} radiusKm={} error={}",
                            lat, lng, radiusKm, ex.getMessage());
                    status.setRollbackOnly();
                    return List.of();
                }
            });
        } catch (Exception ex) {
            log.debug("POSTGIS_NEARBY_FAILED lat={} lng={} radiusKm={} error={}",
                    lat, lng, radiusKm, ex.getMessage());
            return List.of();
        }
    }

    private RestaurantSummary toSummary(Object[] row) {
        Long id = ((Number) row[0]).longValue();
        String name = (String) row[1];
        Double distanceKm = null;
        if (row[4] != null) {
            distanceKm = ((Number) row[4]).doubleValue() / 1000.0;
        }
        return new RestaurantSummary(
                id, name, null, null, null, null, true, distanceKm == null ? 0.0 : distanceKm);
    }
}
