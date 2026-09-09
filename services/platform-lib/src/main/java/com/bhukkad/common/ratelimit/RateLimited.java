package com.bhukkad.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as rate-limited by {@link RateLimitAspect}.
 *
 * @param bucket    the rate-limit bucket (e.g. {@code order-create})
 * @param limit     max calls per window for the default tier
 * @param windowSeconds window length in seconds
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimited {
    String bucket();
    int limit();
    int windowSeconds();
}
