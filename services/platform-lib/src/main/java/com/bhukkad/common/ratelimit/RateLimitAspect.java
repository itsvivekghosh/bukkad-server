package com.bhukkad.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * AOP guard for {@link RateLimited} methods. Runs before the method body;
 * on denial throws {@link RateLimitExceededException} which the shared
 * {@link com.bhukkad.common.web.GlobalExceptionHandler} maps to 429.
 *
 * <p>Registered as a bean so {@code @RateLimited} works in every service that
 * component-scans {@code com.bhukkad.common}. Key resolution prefers an
 * injected {@link RateLimitKeyResolver} (e.g. the request/IP-aware resolver
 * in {@code common.web}); the fallback buckets on the authenticated principal
 * id. The previous always-{@code "default"} resolver collapsed all callers
 * into one bucket, letting one client exhaust everyone's quota.</p>
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimitService rateLimitService;
    private final ObjectProvider<RateLimitKeyResolver> keyResolverProvider;

    public interface RateLimitKeyResolver {
        String resolve(ProceedingJoinPoint joinPoint);
    }

    /** Fallback resolver: authenticated subject, else a shared bucket. */
    public static final RateLimitKeyResolver DEFAULT_KEY_RESOLVER = joinPoint -> {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof com.bhukkad.common.security.TokenPrincipal principal) {
            return "user:" + principal.userId();
        }
        return "anonymous";
    };

    @Around("@annotation(rateLimited)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimited rateLimited) throws Throwable {
        RateLimitKeyResolver resolver = keyResolverProvider.getIfAvailable();
        String key = (resolver != null ? resolver : DEFAULT_KEY_RESOLVER).resolve(joinPoint);
        RateLimitDecision decision = rateLimitService.check(
                rateLimited.bucket(), key, rateLimited.limit(), rateLimited.windowSeconds());
        if (!decision.allowed()) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded for " + rateLimited.bucket(), decision.retryAfterSeconds());
        }
        return joinPoint.proceed();
    }
}
