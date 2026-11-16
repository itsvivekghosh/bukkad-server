package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Exposes Hibernate query plan cache hit ratio as a Prometheus metric.
 *
 * <p>Hibernate caches compiled query plans to avoid re-parsing SQL on every
 * execution. A low hit ratio indicates the plan cache is too small and plans
 * are being evicted/recompiled frequently, which adds CPU overhead on every
 * query.</p>
 *
 * <p>Exposes {@code hibernate.query_plan_cache_hit_ratio} as a gauge (0.0-1.0).
 * Alert when below 0.90 for sustained periods.</p>
 */
@Component
public class QueryPlanCacheMetrics {

    private static final String HIT_RATIO_METRIC = "hibernate.query_plan_cache_hit_ratio";

    public QueryPlanCacheMetrics(EntityManagerFactory entityManagerFactory, MeterRegistry registry) {
        Objects.requireNonNull(entityManagerFactory, "entityManagerFactory");
        Objects.requireNonNull(registry, "registry");

        Gauge.builder(HIT_RATIO_METRIC, entityManagerFactory, emf -> {
            try {
                // Attempt to read JPA query plan cache statistics via Hibernate's
                // SessionFactoryImplementor API. The exact API varies by Hibernate
                // version, so we use reflection to remain version-agnostic.
                var sfImpl = emf.unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class);
                var method = sfImpl.getClass().getMethod("getQueryPlanCache");
                var queryPlanCache = method.invoke(sfImpl);
                if (queryPlanCache == null) {
                    return 0.0;
                }
                var region = queryPlanCache.getClass().getMethod("getQueryPlanRegion").invoke(queryPlanCache);
                if (region == null) {
                    return 0.0;
                }
                var stats = region.getClass().getMethod("getStatistics").invoke(region);
                var getPutCount = stats.getClass().getMethod("getPutCount");
                var getHitCount = stats.getClass().getMethod("getHitCount");
                var getMissCount = stats.getClass().getMethod("getMissCount");
                double puts = (Long) getPutCount.invoke(stats);
                double hits = (Long) getHitCount.invoke(stats);
                double misses = (Long) getMissCount.invoke(stats);
                double total = puts + hits + misses;
                if (total <= 0) {
                    return 1.0;
                }
                return hits / total;
            } catch (Exception e) {
                // Best-effort metric; return 0.0 if we cannot read stats
                return 0.0;
            }
        }).description("Hibernate query plan cache hit ratio (0.0-1.0). Alert below 0.90.")
          .register(registry);
    }
}