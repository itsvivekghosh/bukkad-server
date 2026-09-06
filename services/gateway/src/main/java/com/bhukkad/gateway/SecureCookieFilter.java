package com.bhukkad.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Hardens every {@code Set-Cookie} response passing through the edge
 * (audit batch A, secure-cookies filter): cookies are rewritten to
 * {@code HttpOnly}, {@code Secure} and {@code SameSite=Strict} — session
 * cookies the platform services set must never be readable from script or
 * sent to a cross-site context, regardless of what an upstream forgot to set.
 *
 * <p>Runs at response-commit time and is a no-op when the upstream set no
 * cookies. Attribute handling is surgical: only {@code HttpOnly},
 * {@code Secure} and {@code SameSite} are rewritten/replaced — path, domain,
 * max-age, expires and anything else pass through untouched.</p>
 */
@Component
public class SecureCookieFilter implements GlobalFilter, Ordered {

    static final int ORDER = -35; // response-side hardening, after transport hygiene

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            List<String> setCookies = headers.getOrEmpty(HttpHeaders.SET_COOKIE);
            if (setCookies.isEmpty()) {
                return Mono.empty(); // skip when no cookies
            }
            List<String> hardened = new ArrayList<>(setCookies.size());
            for (String raw : setCookies) {
                String rewritten = harden(raw);
                hardened.add(rewritten != null ? rewritten : raw);
            }
            headers.put(HttpHeaders.SET_COOKIE, hardened);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    /**
     * @return the hardened {@code Set-Cookie} value, or {@code null} when the
     *         raw header does not carry a parseable {@code name=value} pair
     */
    static String harden(String raw) {
        int semicolon = raw.indexOf(';');
        String nameValuePair = (semicolon >= 0 ? raw.substring(0, semicolon) : raw).trim();
        if (nameValuePair.isEmpty() || !nameValuePair.contains("=")) {
            return null;
        }
        StringBuilder hardened = new StringBuilder(nameValuePair);
        if (semicolon >= 0) {
            for (String attribute : raw.substring(semicolon + 1).split(";")) {
                String trimmed = attribute.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String key = trimmed.split("=", 2)[0].trim();
                String lower = key.toLowerCase();
                if (lower.equals("httponly") || lower.equals("secure") || lower.equals("samesite")) {
                    continue; // re-added, hardened, below
                }
                hardened.append("; ").append(trimmed);
            }
        }
        hardened.append("; HttpOnly; Secure; SameSite=Strict");
        return hardened.toString();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
