package com.bhukkad.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The edge's own writer for the platform ApiError envelope
 * ({@code status/code/message/path/timestamp} — the shape
 * {@link UpstreamUnavailableHandler} emits for 503s). Route-level rejections
 * (429 rate limit, unmatched-route 404) answer through this so every gateway
 * response carries the same body shape as the services behind it, instead of
 * the bare container default (audit V-20: invisible 404s).
 */
final class EdgeApiErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EdgeApiErrors() {
        // Static writer.
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("code", code);
        body.put("message", message);
        body.put("path", exchange.getRequest().getURI().getPath());
        body.put("timestamp", Instant.now().toString());
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(body);
        } catch (Exception jsonFailure) {
            bytes = ("{\"status\":" + status.value() + ",\"code\":\"" + code + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        return exchange.getResponse().writeWith(Mono.just(bufferFactory.wrap(bytes)));
    }
}
