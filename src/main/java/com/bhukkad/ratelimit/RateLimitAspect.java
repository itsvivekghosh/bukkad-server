package com.bhukkad.ratelimit;

import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.exception.RateLimitExceededException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

@Aspect
@Component
@Order(50)
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimitService rateLimitService;
    private final SecurityUtils securityUtils;
    private final UserTierResolver userTierResolver;
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(rateLimited)")
    public Object enforceRateLimit(ProceedingJoinPoint joinPoint, RateLimited rateLimited) throws Throwable {
        String bucket = rateLimited.value();
        String identifier = buildIdentifier(bucket, joinPoint);
        String tier = userTierResolver.resolveCurrentTier();
        RateLimitDecision decision = rateLimitService.check(bucket, identifier, tier);

        if (!decision.allowed()) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded. Try again in " + decision.retryAfterSeconds() + " seconds.",
                    decision.retryAfterSeconds());
        }

        return joinPoint.proceed();
    }

    private String buildIdentifier(String bucket, ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        Object[] args = joinPoint.getArgs();

        if ("auth-login".equals(bucket)) {
            return "login:" + resolveLoginEmail(args);
        }

        Long userId = resolveCurrentUserId();

        Long orderId = findLongArg(parameterNames, args, "orderId");
        Long restaurantId = findLongArg(parameterNames, args, "restaurantId");
        String keyword = findStringArg(parameterNames, args, "keyword");

        if (orderId == null) {
            orderId = firstLongArg(args);
        }
        if (restaurantId == null) {
            restaurantId = firstLongArg(args);
        }

        String userKey = userId != null ? String.valueOf(userId) : "anonymous";

        return switch (bucket) {
            case "order-track" -> "user:" + userKey + ":order:" + orderId;
            case "kitchen-queue" -> "user:" + userKey + ":restaurant:" + restaurantId;
            case "search" -> "search:" + (StringUtils.hasText(keyword) ? keyword.toLowerCase().trim() : "all")
                    + ":user:" + userKey;
            case "cart-mutation" -> "user:" + userKey + ":cart";
            default -> "user:" + userKey;
        };
    }

    private Long resolveCurrentUserId() {
        try {
            return securityUtils.getCurrentUserId();
        } catch (UnauthorizedException ex) {
            return null;
        }
    }

    private String resolveLoginEmail(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof LoginRequest login && StringUtils.hasText(login.getEmail())) {
                return login.getEmail().toLowerCase().trim();
            }
        }
        return "unknown";
    }

    private Long findLongArg(String[] parameterNames, Object[] args, String name) {
        if (parameterNames == null) {
            return null;
        }
        for (int i = 0; i < parameterNames.length; i++) {
            if (name.equals(parameterNames[i]) && args[i] instanceof Long value) {
                return value;
            }
        }
        return null;
    }

    private String findStringArg(String[] parameterNames, Object[] args, String name) {
        if (parameterNames == null) {
            return null;
        }
        for (int i = 0; i < parameterNames.length; i++) {
            if (name.equals(parameterNames[i]) && args[i] instanceof String value) {
                return value;
            }
        }
        return null;
    }

    private Long firstLongArg(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Long value) {
                return value;
            }
        }
        return null;
    }
}
