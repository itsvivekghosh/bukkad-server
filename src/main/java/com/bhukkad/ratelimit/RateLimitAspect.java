package com.bhukkad.ratelimit;

import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.exception.RateLimitExceededException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.security.SecurityUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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

    @Value("${app.rate-limit.headers-enabled:true}")
    boolean headersEnabled = true;

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

        if (headersEnabled) {
            setRateLimitHeaders(decision);
        }

        return joinPoint.proceed();
    }

    /**
     * Sets {@code X-RateLimit-*} headers on the current HTTP response so
     * clients can self-throttle. The headers are informational: a client may
     * still be blocked by the hard 429 even when the remaining count is positive
     * (another request may consume the budget between the read and the write).
     */
    private void setRateLimitHeaders(RateLimitDecision decision) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return;
        }
        HttpServletResponse response = attrs.getResponse();
        if (response == null) {
            return;
        }
        long remaining = Math.max(0, decision.limit() - decision.currentCount());
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.windowSeconds()));
    }

    private String buildIdentifier(String bucket, ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        Object[] args = joinPoint.getArgs();

        if ("auth-login".equals(bucket)) {
            return "login:" + resolveLoginEmail(args);
        }
        if ("auth-register".equals(bucket)) {
            // Per-IP + device + email to avoid global bucket starvation.
            // Previous "user:anonymous" meant 10 registrations/min platform-wide.
            String ip = resolveClientIp();
            String device = resolveDeviceId();
            String email = resolveRegisterEmail(args);
            return "register:ip:" + ip + ":device:" + device + (email != null ? ":email:" + email : "");
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

        String userKey;
        if (userId != null) {
            userKey = String.valueOf(userId);
        } else {
            // For anonymous buckets (e.g. webhook, search unauthed), scope by IP
            // instead of global "anonymous" to prevent cross-user throttling.
            userKey = "anon:ip:" + resolveClientIp();
        }

        return switch (bucket) {
            case "order-track" -> "user:" + userKey + ":order:" + orderId;
            case "kitchen-queue" -> "user:" + userKey + ":restaurant:" + restaurantId;
            case "search" -> "search:" + (StringUtils.hasText(keyword) ? keyword.toLowerCase().trim() : "all")
                    + ":user:" + userKey;
            case "cart-mutation" -> "user:" + userKey + ":cart";
            default -> "user:" + userKey;
        };
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return "unknown";
            var request = attrs.getRequest();
            String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
            for (String h : headers) {
                String v = request.getHeader(h);
                if (v != null && !v.isBlank() && !"unknown".equalsIgnoreCase(v)) {
                    return v.split(",")[0].trim();
                }
            }
            return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String resolveDeviceId() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return "unknown-device";
            var request = attrs.getRequest();
            String device = request.getHeader("X-Device-Id");
            if (StringUtils.hasText(device)) return device.trim();
            device = request.getHeader("X-Device-Fingerprint");
            if (StringUtils.hasText(device)) return device.trim();
            String param = request.getParameter("deviceId");
            if (StringUtils.hasText(param)) return param.trim();
            return "unknown-device";
        } catch (Exception e) {
            return "unknown-device";
        }
    }

    private String resolveRegisterEmail(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof com.bhukkad.dto.request.RegisterRequest reg) {
                if (StringUtils.hasText(reg.getEmail())) return reg.getEmail().toLowerCase().trim();
                if (StringUtils.hasText(reg.getPhoneNumber())) return reg.getPhoneNumber().trim();
            }
        }
        return null;
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