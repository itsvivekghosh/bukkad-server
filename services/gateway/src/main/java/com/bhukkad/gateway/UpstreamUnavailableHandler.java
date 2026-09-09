package com.bhukkad.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Upstream-unreachable responses for the gateway.
 *
 * <p>Without this, a service being down (DNS failure / connection refused /
 * no healthy instance) surfaced as an opaque HTTP 500 "internal server error"
 * at the edge — indistinguishable to clients and monitors from a real server
 * fault. Route failures are a distinct, retriable class of failure and are
 * now mapped to a clean {@code 503 UPSTREAM_UNAVAILABLE} envelope matching
 * the platform ApiError shape.</p>
 */
@Component
@Order(-2) // run before Boot's default error handler (-1)
public class UpstreamUnavailableHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!isUpstreamFailure(ex)) {
            return Mono.error(ex); // leave everything else to default handling
        }
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("code", "UPSTREAM_UNAVAILABLE");
        body.put("message", "The requested service is temporarily unavailable. Please retry.");
        body.put("path", exchange.getRequest().getURI().getPath());
        body.put("timestamp", Instant.now().toString());
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().add("Retry-After", "5");
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (Exception jsonFailure) {
            bytes = ("{\"status\":503,\"code\":\"UPSTREAM_UNAVAILABLE\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        return exchange.getResponse().writeWith(
                Mono.just(bufferFactory.wrap(bytes)));
    }

    private static boolean isUpstreamFailure(Throwable ex) {
        for (Throwable t = ex; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof TimeoutException
                    || t.getClass().getName().startsWith("io.netty.resolver.dns.Dns")
                    || t.getClass().getSimpleName().equals("WebClientRequestException")) {
                return true;
            }
        }
        return false;
    }
}
