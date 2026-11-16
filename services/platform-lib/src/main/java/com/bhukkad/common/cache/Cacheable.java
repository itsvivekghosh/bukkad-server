package com.bhukkad.common.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for declarative caching on service methods.
 *
 * <p>When applied to a method, {@link CacheAspect} intercepts the call and
 * serves the result from {@link RedisCacheService} if available, otherwise
 * falls through to direct execution.
 *
 * <pre>{@code
 * @Cacheable(key = "'order:' + #orderId", ttl = 30, value = "orders")
 * public OrderResponse getOrder(Long orderId) { ... }
 * }</pre>
 *
 * OPT-026/027: Provides declarative caching without polluting business logic
 * with cache implementation details.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Cacheable {
    /** Cache key expression (supports simple SpEL-like #param references). */
    String key() default "";

    /** Cache value/namespace for grouping. */
    String value() default "";

    /** TTL in seconds. */
    long ttl() default 30;
}
