package com.bhukkad.common.web;

import com.bhukkad.common.ratelimit.RateLimitAspect;
import com.bhukkad.common.security.TokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Request-aware rate-limit key resolver: buckets on the authenticated subject
 * when present, else on the caller's remote address (X-Forwarded-For is
 * intentionally NOT honoured — client-spoofable). Lives in {@code common.web}
 * because it touches the servlet API (see CommonArchTest).
 */
@Component
public class WebRateLimitKeyResolver implements RateLimitAspect.RateLimitKeyResolver {

    @Override
    public String resolve(ProceedingJoinPoint joinPoint) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof TokenPrincipal principal) {
            return "user:" + principal.userId();
        }
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            String ip = request.getRemoteAddr();
            if (ip != null && !ip.isBlank()) {
                return "ip:" + ip;
            }
        }
        return "anonymous";
    }
}
