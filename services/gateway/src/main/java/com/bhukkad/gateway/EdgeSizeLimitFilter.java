package com.bhukkad.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Request/response size guard for the edge.
 *
 * <p>Rejects requests whose {@code Content-Length} exceeds the configured
 * maximum before the body is buffered or forwarded. Responses larger than the
 * limit are mapped to 413 Payload Too Large so the client knows immediately
 * rather than timing out on a partial stream.</p>
 */
@Component
public class EdgeSizeLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(EdgeSizeLimitFilter.class);
    static final int ORDER = -25; // after header hygiene (-40), before rate limit (-30)
    private static final long DEFAULT_MAX_REQUEST_BYTES = 2L * 1024 * 1024; // 2 MB
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 10L * 1024 * 1024; // 10 MB

    private final long maxRequestBytes;
    private final long maxResponseBytes;

    public EdgeSizeLimitFilter(
            @org.springframework.beans.factory.annotation.Value("${app.edge.size-limit.max-request-bytes:2097152}") long maxRequestBytes,
            @org.springframework.beans.factory.annotation.Value("${app.edge.size-limit.max-response-bytes:10485760}") long maxResponseBytes) {
        this.maxRequestBytes = maxRequestBytes > 0 ? maxRequestBytes : DEFAULT_MAX_REQUEST_BYTES;
        this.maxResponseBytes = maxResponseBytes > 0 ? maxResponseBytes : DEFAULT_MAX_RESPONSE_BYTES;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String contentLength = request.getHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                long length = Long.parseLong(contentLength);
                if (length > maxRequestBytes) {
                    log.warn("EDGE_REQUEST_TOO_LARGE contentLength={} limit={} path={}", length, maxRequestBytes, request.getURI().getPath());
                    return deny(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds " + (maxRequestBytes / 1024 / 1024) + " MB limit");
                }
            } catch (NumberFormatException ignored) {
                // Non-numeric Content-Length (e.g. chunked); let downstream buffer/handle it
            }
        }
        return chain.filter(exchange)
                .doOnSuccess(v -> guardResponse(exchange))
                .doOnError(t -> exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private Mono<Void> guardResponse(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        Long contentLength = response.getHeaders().getContentLength();
        if (contentLength != null && contentLength > maxResponseBytes) {
            log.warn("EDGE_RESPONSE_TOO_LARGE contentLength={} limit={} path={}", contentLength, maxResponseBytes, exchange.getRequest().getURI().getPath());
            response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            response.getHeaders().setContentLength(0);
            return response.writeWith(Mono.empty());
        }
        return Mono.empty();
    }

    private static Mono<Void> deny(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().set("Content-Type", "application/json");
        String body = "{\"status\":" + status.value() + ",\"code\":\"" + status.name() + "\",\"message\":\"" + message + "\"}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes())));
    }
}
