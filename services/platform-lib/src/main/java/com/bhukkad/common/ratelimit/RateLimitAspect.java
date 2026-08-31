package com.bhukkad.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * AOP guard for {@link RateLimited} methods. Runs before the method body;
 * on denial throws {@link RateLimitExceededException} which the shared
 * {@link com.bhukkad.common.web.GlobalExceptionHandler} maps to 429.
 */
@Aspect
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimitService rateLimitService;
    private final RateLimitKeyResolver keyResolver;

    public interface RateLimitKeyResolver {
        String resolve(ProceedingJoinPoint joinPoint);
    }

    /** Default resolver: caller identity must be supplied by the service (e.g. auth header). */
    public static final RateLimitKeyResolver DEFAULT_KEY_RESOLVER = joinPoint -> "default";

    @Around("@annotation(rateLimited)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimited rateLimited) throws Throwable {
        String key = keyResolver.resolve(joinPoint);
        RateLimitDecision decision = rateLimitService.check(
                rateLimited.bucket(), key, rateLimited.limit(), rateLimited.windowSeconds());
        if (!decision.allowed()) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded for " + rateLimited.bucket(), decision.retryAfterSeconds());
        }
        return joinPoint.proceed();
    }
}
