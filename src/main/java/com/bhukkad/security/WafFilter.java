package com.bhukkad.security;

import com.bhukkad.exception.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;
import java.util.regex.Pattern;

/**
 * Application-layer WAF: rejects obvious SQL injection and XSS payloads in
 * query parameters before they reach controllers. This is a defense-in-depth
 * filter — a network-level WAF should still be deployed at the gateway.
 */
@Slf4j
@Component
@Order(0)
public class WafFilter extends OncePerRequestFilter {

    // SQLi: classic tautologies, stacked statements, comments, UNION selects.
    private static final Pattern SQLI_PATTERN = Pattern.compile(
            "(?i)(\\b(select|union|insert|update|delete|drop|alter)\\b.*\\b(from|into|table|set)\\b)"
                    + "|(\\b(or|and)\\b\\s+['\"]?\\d+['\"]?\\s*=\\s*['\"]?\\d+['\"]?)"
                    + "|(--|;\\s*(select|drop|delete|insert|update))"
                    + "|(/\\*.*?\\*/)",
            Pattern.DOTALL);

    // XSS: script tags, event handlers, javascript: URIs, expressions.
    private static final Pattern XSS_PATTERN = Pattern.compile(
            "(?i)(<script[^>]*>|</script>|javascript\\s*:|on(load|error|click|mouseover|focus|blur)\\s*=\\s*['\"]?[^'\"]*['\"]?|\\bexpression\\s*\\()",
            Pattern.DOTALL);

    private static final int MAX_VALUE_LENGTH = 2000;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (isSuspicious(request)) {
            String uri = request.getRequestURI();
            log.warn("WAF_BLOCKED | uri={} | ip={}", uri, request.getRemoteAddr());
            throw new BusinessException("Request blocked by security policy");
        }
        filterChain.doFilter(request, response);
    }

    private boolean isSuspicious(HttpServletRequest request) {
        Enumeration<String> names = request.getParameterNames();
        if (names == null) {
            return false;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (matches(name)) {
                return true;
            }
            for (String value : request.getParameterValues(name)) {
                if (matches(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matches(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
            return false;
        }
        return SQLI_PATTERN.matcher(value).find() || XSS_PATTERN.matcher(value).find();
    }
}
