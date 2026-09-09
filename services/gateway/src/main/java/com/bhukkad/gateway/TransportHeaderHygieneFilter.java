package com.bhukkad.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Transport-header hygiene for every proxied exchange (audit batch A).
 *
 * <p>Request direction: the hop-by-hop {@code Proxy-Connection} never reaches
 * a backend, and {@code TE} is allowed only as {@code trailers} (HTTP/2
 * flow-control trailers) — any other transfer-encoding negotiation is
 * stripped before the request is routed.</p>
 *
 * <p>Response direction: duplicate {@code Access-Control-Allow-*} headers
 * (both the gateway's {@code CorsWebFilter} and an upstream service may emit
 * them) are deduplicated keeping the first value, because browsers reject
 * responses carrying more than one {@code Access-Control-Allow-Origin}.</p>
 *
 * <p>Registered as a {@link GlobalFilter} rather than
 * {@code spring.cloud.gateway.default-filters}: the default-filters property
 * only decorates routes parsed from configuration, and this gateway's route
 * table is a programmatic {@code RouteLocator} — a global filter is the only
 * mechanism that covers all of it.</p>
 */
@Component
public class TransportHeaderHygieneFilter implements GlobalFilter, Ordered {

    static final int ORDER = -40; // just after the UntrustedHeaderSanitizer (=-50)

    private static final String TRAILERS = "trailers";
    private static final String PROXY_CONNECTION = "Proxy-Connection";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            dedupeCorsHeaders(exchange.getResponse().getHeaders());
            return Mono.empty();
        });
        return chain.filter(sanitizeRequest(exchange));
    }

    /** Removes {@code Proxy-Connection} and non-trailers {@code TE}; returns the exchange unchanged when nothing applies. */
    static ServerWebExchange sanitizeRequest(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        boolean dropProxyConnection = headers.containsKey(PROXY_CONNECTION);
        boolean dropTe = !isTrailersOnly(headers.getOrEmpty(HttpHeaders.TE));

        if (!dropProxyConnection && !dropTe) {
            return exchange;
        }
        return exchange.mutate()
                .request(builder -> builder.headers(h -> {
                    if (dropProxyConnection) {
                        h.remove(PROXY_CONNECTION);
                    }
                    if (dropTe) {
                        h.remove(HttpHeaders.TE);
                    }
                }))
                .build();
    }

    /** {@code TE} survives only when every token is "trailers" (case-insensitive); an absent TE is compliant. */
    static boolean isTrailersOnly(List<String> teValues) {
        if (teValues == null || teValues.isEmpty()) {
            return true;
        }
        for (String value : teValues) {
            for (String token : value.split(",")) {
                if (!TRAILERS.equalsIgnoreCase(token.trim())) {
                    return false;
                }
            }
        }
        return true;
    }

    static void dedupeCorsHeaders(HttpHeaders headers) {
        for (String name : List.copyOf(headers.keySet())) {
            if (isCORSAllowHeader(name) && headers.get(name) != null && headers.get(name).size() > 1) {
                String first = headers.get(name).get(0);
                headers.set(name, first);
            }
        }
    }

    private static boolean isCORSAllowHeader(String name) {
        return name.regionMatches(true, 0, "Access-Control-Allow-", 0, "Access-Control-Allow-".length());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
