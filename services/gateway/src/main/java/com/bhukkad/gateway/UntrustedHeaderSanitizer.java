package com.bhukkad.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Strips edge-untrusted identity headers before proxying upstream.
 *
 * <p>Clients must never be able to claim service identity: a forwarded
 * {@code X-Service-Token} (or spoofed forwarding headers) would let an
 * external caller impersonate an internal service once any upstream trusts
 * those headers. Only {@code X-Customer-Id} is removed too — services must
 * derive customer identity from the validated JWT, not from a client-supplied
 * header.</p>
 */
@Component
public class UntrustedHeaderSanitizer implements GlobalFilter, Ordered {

    static final int ORDER = -50; // run before routing and the kill switch

    private static final String[] UNTRUSTED_HEADERS = {
            "X-Service-Token",
            "X-Customer-Id",
            "X-Forwarded-Host",
            "X-Forwarded-Proto",
            "X-Forwarded-Port",
            "X-Forwarded-Prefix",
            "X-Original-URL",
            "X-Rewrite-URL"
    };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        var headers = request.getHeaders();
        boolean mutated = false;
        var builder = request.mutate();
        for (String header : UNTRUSTED_HEADERS) {
            if (headers.containsKey(header)) {
                builder.headers(h -> h.remove(header));
                mutated = true;
            }
        }
        return chain.filter(mutated ? exchange.mutate().request(builder.build()).build() : exchange);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
