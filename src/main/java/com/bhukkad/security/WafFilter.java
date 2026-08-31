package com.bhukkad.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
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
            response.sendError(HttpStatus.BAD_REQUEST.value(), "Request blocked by security policy");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isSuspicious(HttpServletRequest request) {
        Enumeration<String> names = request.getParameterNames();
        if (names != null) {
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
        }
        // Also inspect JSON / form body for SQLi/XSS — query-param check alone
        // is bypassable via POST { "name": "<script>" }.
        String contentType = request.getContentType();
        if (contentType != null && (contentType.contains("json") || contentType.contains("form-urlencoded"))) {
            try {
                // Use cached body if available, otherwise read stream with limit
                String body = null;
                if (request instanceof com.bhukkad.logging.CachedBodyHttpServletRequest cached) {
                    body = cached.getBody();
                } else {
                    // Fallback: read up to MAX_VALUE_LENGTH*2 chars to avoid OOM
                    var reader = request.getReader();
                    if (reader != null) {
                        char[] buf = new char[MAX_VALUE_LENGTH * 2];
                        int read = reader.read(buf);
                        if (read > 0) {
                            body = new String(buf, 0, read);
                        }
                    }
                }
                if (body != null && !body.isBlank() && matches(stripEncryptedFields(body))) {
                    return true;
                }
            } catch (Exception e) {
                // Fail open for body read errors — log and continue
                log.debug("WAF body inspect failed: {}", e.getMessage());
            }
        }
        // Also check relevant headers that may carry injection (e.g. X-Forwarded-For abuse is already handled,
        // but custom headers like X-Search-Keyword could be used)
        return false;
    }

    private boolean matches(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
            return false;
        }
        return SQLI_PATTERN.matcher(value).find() || XSS_PATTERN.matcher(value).find();
    }

    /**
     * Removes JWE-encrypted password fields from a JSON body before pattern
     * matching. The ciphertext is base64url, which legitimately contains {@code -}
     * and {@code _}; random ciphertext can therefore include {@code --}, {@code /*}
     * or {@code ;} sequences that match SQLi/XSS patterns and would cause the WAF
     * to block valid auth requests intermittently. The encrypted value is
     * untrusted input, but pattern-matching ciphertext provides no security — the
     * decryption layer (JwePasswordCrypto + nonce validation) enforces integrity.
     */
    private static String stripEncryptedFields(String body) {
        return body.replaceAll("\"encryptedPassword\"\\s*:\\s*\"[^\"]*\"",
                "\"encryptedPassword\":\"<encrypted>\"");
    }
}
