package com.bhukkad.common.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Caches an <em>empty</em> (negative) result for a short TTL so repeated
 * lookups for a genuinely missing entity short-circuit against L2 (Redis)
 * instead of re-hitting the database.
 *
 * <p>Wired by {@link NegativeCacheAspect}, which reuses the existing
 * {@link RedisCacheService}/{@link LocalCacheService} primitives (the same
 * wiring as {@link CacheAspect}). This does not introduce a parallel cache
 * system.</p>
 *
 * <pre>{@code
 * @NegativeCacheable(cacheName = "restaurant", key = "#restaurantId", ttlSeconds = 10)
 * public Optional<Restaurant> findRestaurant(Long restaurantId) { ... }
 * }</pre>
 *
 * <p>When the intercepted method returns an empty result
 * ({@code null}, empty {@link java.util.Optional}, or empty collection/array),
 * the aspect stores a negative marker in Redis for the configured TTL.
 * Subsequent calls within that window return empty without invoking the
 * method again. A non-empty result is returned as-is (and may additionally
 * be cached by a separate {@link Cacheable} annotation).</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NegativeCacheable {

    /** Cache value/namespace for grouping (mirrors {@link Cacheable#value()}). */
    String cacheName() default "";

    /** Cache key expression (supports simple {@code #param} references). */
    String key() default "";

    /** TTL in seconds for the negative (empty) entry. Defaults to 5s. */
    long ttlSeconds() default 5;
}